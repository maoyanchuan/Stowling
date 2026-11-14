package dev.stowling.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TriggerKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @TempDir
    Path tempDir;

    @Test
    void loadsAndValidatesExampleConfiguration() {
        Path path = Path.of("src/main/resources/stowling.example.json");

        StowlingConfig config = loader.load(path);

        assertEquals(5, config.schemaVersion());
        assertEquals(Duration.ZERO, config.defaultIdleDelay());
        assertTrue(config.coordinatePoints().isEmpty());
        assertTrue(config.pickupRegions().isEmpty());
        assertTrue(config.cacheContainers().isEmpty());
        assertTrue(config.pickupWorkflows().isEmpty());
        assertTrue(config.automationWorkflows().isEmpty());
        assertTrue(config.storageRoutes().isEmpty());
        assertTrue(config.packingStations().isEmpty());
        assertTrue(config.auditSchedules().isEmpty());
        assertEquals("overflow", config.overflowContainer().id());
    }

    @Test
    void canonicalConstructorUpgradesProgrammaticSchemaFour() {
        StowlingConfig source = ConfigTestFixtures.config(List.of());

        StowlingConfig config = new StowlingConfig(
                4,
                source.coordinatePoints(),
                source.pickupRegions(),
                source.cacheContainers(),
                source.pickupWorkflows(),
                List.of(),
                source.storageRoutes(),
                source.packingStations(),
                source.auditSchedules(),
                source.overflowContainer()
        );

        assertEquals(StowlingConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion());
    }

    @Test
    void migratesV1SitesAndDeduplicatesCaches() throws IOException {
        String json = """
                {
                    "schemaVersion": 1,
                    "defaultIdleDelay": 90,
                    "pickupSites": [
                        {
                            "id": "north-farm",
                            "dimension": "minecraft:overworld",
                            "center": {"x": 0, "y": 64, "z": 0},
                            "radius": 32,
                            "cacheContainers": [
                                {
                                    "id": "north-cache",
                                    "dimension": "minecraft:overworld",
                                    "position": {"x": 8, "y": 64, "z": 0},
                                    "type": "CHEST"
                                }
                            ],
                            "idleDelay": 60
                        },
                        {
                            "id": "south-farm",
                            "dimension": "minecraft:overworld",
                            "center": {"x": 0, "y": 64, "z": 20},
                            "radius": 16,
                            "cacheContainers": [
                                {
                                    "id": "shared-cache",
                                    "dimension": "minecraft:overworld",
                                    "position": {"x": 8, "y": 64, "z": 0},
                                    "type": "CHEST"
                                }
                            ],
                            "idleDelay": 30
                        }
                    ],
                    "storageRoutes": [],
                    "packingStations": [],
                    "auditSchedules": [],
                    "overflowContainer": {
                        "id": "overflow",
                        "dimension": "minecraft:overworld",
                        "position": {"x": 24, "y": 64, "z": 0},
                        "type": "CHEST"
                    }
                }
                """;

        StowlingConfig config = loader.parse(json);

        assertEquals(5, config.schemaVersion());
        assertEquals(2, config.pickupRegions().size());
        assertEquals(1, config.cacheContainers().size());
        assertEquals(2, config.pickupWorkflows().size());
        assertTrue(config.pickupRegions().getFirst().shape() instanceof SpherePickupShape);
        assertEquals(
                ClassificationTiming.AFTER_ROUND,
                config.pickupWorkflows().getFirst().classificationTiming()
        );
        assertEquals(
                CompletionMode.ALL_IDLE,
                config.pickupWorkflows().getFirst().completionMode()
        );
        assertEquals(
                config.pickupWorkflows().getFirst().cacheIds(),
                config.pickupWorkflows().getLast().cacheIds()
        );
        PickupRegion firstRegion = config.pickupRegions().getFirst();
        SpherePickupShape firstShape = (SpherePickupShape) firstRegion.shape();
        assertEquals(firstRegion.standbyCoordinateId(), firstShape.centerCoordinateId());

        Path output = tempDir.resolve("migrated.json");
        loader.write(output, config);
        String written = Files.readString(output);
        assertTrue(written.contains("\"schemaVersion\": 5"));
        assertTrue(written.contains("\"coordinatePoints\""));
        assertFalse(written.contains("pickupSites"));
        assertFalse(written.contains("defaultIdleDelay"));
    }

    @Test
    void migratesV2CoordinatesByDimensionAndPosition() throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/legacy-pickup-example.json"))
        ).getAsJsonObject();
        root.addProperty("schemaVersion", 2);
        root.remove("coordinatePoints");
        root.remove("automationWorkflows");
        JsonObject region = root.getAsJsonArray("pickupRegions").get(0).getAsJsonObject();
        region.remove("standbyCoordinateId");
        JsonObject shape = region.getAsJsonObject("shape");
        shape.remove("centerCoordinateId");
        JsonObject cache = root.getAsJsonArray("cacheContainers").get(0).getAsJsonObject();
        cache.remove("coordinateId");

        StowlingConfig config = loader.parse(root.toString());

        assertEquals(5, config.schemaVersion());
        assertEquals(2, config.coordinatePoints().size());
        PickupRegion migratedRegion = config.pickupRegions().getFirst();
        SpherePickupShape migratedShape = (SpherePickupShape) migratedRegion.shape();
        assertEquals(
                migratedRegion.standbyCoordinateId(),
                migratedShape.centerCoordinateId()
        );
    }

    @Test
    void migratesV3ByAddingAnEmptyAutomationWorkflowList() throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/legacy-pickup-example.json"))
        ).getAsJsonObject();
        root.addProperty("schemaVersion", 3);
        root.remove("automationWorkflows");

        StowlingConfig config = loader.parse(root.toString());

        assertEquals(5, config.schemaVersion());
        assertTrue(config.automationWorkflows().isEmpty());
    }

    @Test
    void migratesV4ByAddingDefaultSettingsWithoutEnablingTasks() throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/legacy-pickup-example.json"))
        ).getAsJsonObject();
        root.addProperty("schemaVersion", 4);
        root.getAsJsonArray("pickupWorkflows")
                .get(0)
                .getAsJsonObject()
                .remove("settings");
        JsonObject automation = JsonParser.parseString("""
                {
                    "id": "wait-task",
                    "name": "Wait task",
                    "steps": [{
                        "id": "wait",
                        "type": "WAIT",
                        "parameters": {"seconds": "1"}
                    }]
                }
                """).getAsJsonObject();
        root.getAsJsonArray("automationWorkflows").add(automation);

        StowlingConfig config = loader.parse(root.toString());

        assertEquals(5, config.schemaVersion());
        assertEquals("farm-patrol", config.pickupWorkflows().getFirst().id());
        assertEquals("wait-task", config.automationWorkflows().getFirst().id());
        assertEquals(
                TaskPriority.MEDIUM,
                config.pickupWorkflows().getFirst().settings().priority()
        );
        TaskSettings settings = config.automationWorkflows().getFirst().settings();
        assertEquals(TaskPriority.MEDIUM, settings.priority());
        assertEquals(TriggerKind.MANUAL, settings.trigger().kind());
        assertEquals("Asia/Shanghai", settings.trigger().zoneId());
        assertEquals(InterruptionKind.NONE, settings.interruption().kind());
        assertFalse(settings.autoStartOnRestart());
    }

    @Test
    void roundTripsStrictAutomationParameters() {
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        String coordinateId = base.coordinatePoints().getFirst().id();
        String containerId = base.cacheContainers().getFirst().id();
        String pickupId = base.pickupWorkflows().getFirst().id();
        AutomationWorkflow workflow = new AutomationWorkflow(
                "general_task",
                "General task",
                List.of(
                step("go", AutomationStepType.NAVIGATE, Map.of(
                "coordinateId", coordinateId
        )),
                step("wait", AutomationStepType.WAIT, Map.of("seconds", "2")),
                step("pickup", AutomationStepType.RUN_PICKUP, Map.of(
                "workflowId", pickupId
        )),
                itemStep("take", AutomationStepType.TAKE_ITEMS, containerId),
                itemStep("put", AutomationStepType.PUT_ITEMS, containerId),
                step("move", AutomationStepType.TRANSFER_ITEMS, Map.of(
                "sourceContainerId", containerId,
                "destination", "PLAYER",
                "itemId", "minecraft:stone",
                "count", "3"
        )),
                itemCountStep("check", AutomationStepType.CHECK_INVENTORY),
                itemCountStep("acquire", AutomationStepType.ACQUIRE_MATERIALS),
                itemCountStep("craft", AutomationStepType.CRAFT_ITEM)
        )
        );
        StowlingConfig config = ConfigTestFixtures.configWithAutomation(List.of(workflow));
        Path output = tempDir.resolve("automation-round-trip.json");

        loader.write(output, config);
        StowlingConfig loaded = loader.load(output);

        assertEquals(config, loaded);
        assertEquals(9, loaded.automationWorkflows().getFirst().steps().size());
    }

    @Test
    void rejectsUnknownAutomationModuleType() {
        JsonObject root = JsonParser.parseString(
                """
                        {
                            "schemaVersion": 4,
                            "coordinatePoints": [],
                            "pickupRegions": [],
                            "cacheContainers": [],
                            "pickupWorkflows": [],
                            "automationWorkflows": [{
                                "id": "invalid",
                                "name": "Invalid",
                                "steps": [{
                                    "id": "teleport",
                                    "type": "TELEPORT",
                                    "parameters": {}
                                }]
                            }],
                            "storageRoutes": [],
                            "packingStations": [],
                            "auditSchedules": [],
                            "overflowContainer": {
                                "id": "overflow",
                                "dimension": "minecraft:overworld",
                                "position": {"x": 0, "y": 64, "z": 0},
                                "type": "CHEST"
                            }
                        }
                """
        ).getAsJsonObject();

        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse(root.toString())
        );

        assertTrue(exception.getMessage().contains("parse configuration"));
    }

    @Test
    void coordinateChangesPropagateToAllReferencesAfterReload() throws IOException {
        StowlingConfig config = ConfigTestFixtures.config(List.of());
        Path output = tempDir.resolve("coordinate-propagation.json");
        loader.write(output, config);
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        PickupRegion originalRegion = config.pickupRegions().getFirst();
        String coordinateId = originalRegion.standbyCoordinateId();
        JsonObject coordinate = coordinate(root, coordinateId);
        coordinate.getAsJsonObject("position").addProperty("x", 42);
        Files.writeString(output, root.toString());

        StowlingConfig reloaded = loader.load(output);

        PickupRegion region = reloaded.pickupRegions().getFirst();
        SpherePickupShape shape = (SpherePickupShape) region.shape();
        assertEquals(new BlockPosition(42, 64, 0), region.standbyPosition());
        assertEquals(new BlockPosition(42, 64, 0), shape.center());
    }

    @Test
    void rejectsUnknownCoordinateReferenceWithOwnerPath() throws IOException {
        StowlingConfig config = ConfigTestFixtures.config(List.of());
        Path output = tempDir.resolve("unknown-coordinate.json");
        loader.write(output, config);
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        root.getAsJsonArray("pickupRegions")
                .get(0)
                .getAsJsonObject()
                .addProperty("standbyCoordinateId", "missing-coordinate");

        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse(root.toString())
        );

        assertTrue(exception.getMessage().contains("pickupRegions[0]"));
        assertTrue(exception.getMessage().contains("missing-coordinate"));
    }

    @Test
    void rejectsRegionReferencesAcrossCoordinateDimensions() throws IOException {
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        PickupRegion region = new PickupRegion(
                "split-region",
                "Split region",
                "minecraft:overworld",
                new BlockPosition(20, 64, 0),
                new SpherePickupShape(new BlockPosition(0, 64, 0), 8)
        );
        PickupWorkflow workflow = new PickupWorkflow(
                "split-workflow",
                "Split workflow",
                "minecraft:overworld",
                List.of(new PickupRegionBinding(
                region.id(),
                100,
                Duration.ofSeconds(60)
        )),
                List.of(base.cacheContainers().getFirst().id()),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ofSeconds(300)
        );
        StowlingConfig config = new StowlingConfig(
                2,
                List.of(region),
                base.cacheContainers(),
                List.of(workflow),
                base.storageRoutes(),
                base.packingStations(),
                base.auditSchedules(),
                base.overflowContainer()
        );
        Path output = tempDir.resolve("cross-dimension.json");
        loader.write(output, config);
        JsonObject root = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        SpherePickupShape shape = (SpherePickupShape) config.pickupRegions()
                .getFirst()
                .shape();
        coordinate(root, shape.centerCoordinateId())
                .addProperty("dimension", "minecraft:the_nether");

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> loader.parse(root.toString())
        );

        assertTrue(exception.getMessage().contains("pickup region split-region"));
        assertTrue(exception.getMessage().contains("dimension"));
    }

    private static AutomationStep step(
            String id,
            AutomationStepType type,
            Map<String, String> parameters
    ) {
        return new AutomationStep(id, type, parameters);
    }

    private static AutomationStep itemStep(
            String id,
            AutomationStepType type,
            String containerId
    ) {
        return step(id, type, Map.of(
                "containerId", containerId,
                "itemId", "minecraft:stone",
                "count", "3"
        ));
    }

    private static AutomationStep itemCountStep(
            String id,
            AutomationStepType type
    ) {
        return step(id, type, Map.of(
                "itemId", "minecraft:stone",
                "count", "3"
        ));
    }

    private static JsonObject coordinate(JsonObject root, String id) {
        JsonArray coordinates = root.getAsJsonArray("coordinatePoints");
        for (int index = 0; index < coordinates.size(); index++) {
            JsonObject coordinate = coordinates.get(index).getAsJsonObject();
            if (id.equals(coordinate.get("id").getAsString())) {
                return coordinate;
            }
        }
        throw new AssertionError("missing coordinate " + id);
    }

    @Test
    void rejectsMalformedJson() {
        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse("{")
        );

        assertTrue(exception.getMessage().contains("parse"));
    }

    @Test
    void rejectsV1CacheIdThatPointsToConflictingContainers() {
        String json = """
                {
                    "schemaVersion": 1,
                    "defaultIdleDelay": 60,
                    "pickupSites": [
                        {
                            "id": "north",
                            "dimension": "minecraft:overworld",
                            "center": {"x": 0, "y": 64, "z": 0},
                            "radius": 16,
                            "cacheContainers": [{
                                "id": "shared-cache",
                                "dimension": "minecraft:overworld",
                                "position": {"x": 8, "y": 64, "z": 0},
                                "type": "CHEST"
                            }],
                            "idleDelay": 60
                        },
                        {
                            "id": "south",
                            "dimension": "minecraft:overworld",
                            "center": {"x": 0, "y": 64, "z": 20},
                            "radius": 16,
                            "cacheContainers": [{
                                "id": "shared-cache",
                                "dimension": "minecraft:overworld",
                                "position": {"x": 9, "y": 64, "z": 0},
                                "type": "CHEST"
                            }],
                            "idleDelay": 60
                        }
                    ],
                    "storageRoutes": [],
                    "packingStations": [],
                    "auditSchedules": [],
                    "overflowContainer": {
                        "id": "overflow",
                        "dimension": "minecraft:overworld",
                        "position": {"x": 24, "y": 64, "z": 0},
                        "type": "CHEST"
                    }
                }
                """;

        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse(json)
        );

        assertTrue(exception.getMessage().contains("conflicting containers"));
    }

    @Test
    void rejectsUnknownRootFields() {
        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse("{\"unexpected\": true}")
        );

        assertTrue(exception.getMessage().contains("Unknown configuration field"));
    }

    @Test
    void writesConfigurationWithDurationAsWholeSeconds() {
        Path output = Path.of("build", "tmp", "config-round-trip.json");
        StorageRoute route = ConfigTestFixtures.route(
                "items",
                List.of("minecraft:stone"),
                1
        );
        StowlingConfig config = ConfigTestFixtures.config(List.of(route));

        loader.write(output, config);
        StowlingConfig loaded = loader.load(output);

        assertEquals(config, loaded);
    }

    @Test
    void roundTripsCuboidPickupRegion() {
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        PickupRegion region = new PickupRegion(
                "warehouse-floor",
                "Warehouse floor",
                "minecraft:overworld",
                new BlockPosition(20, 64, 20),
                new CuboidPickupShape(
                        new BlockPosition(0, 60, 0),
                        new BlockPosition(10, 70, 10)
        )
        );
        NamedCache cache = new NamedCache(
                "warehouse-cache",
                "Warehouse cache",
                "minecraft:overworld",
                new BlockPosition(12, 64, 5),
                ContainerType.BARREL
        );
        PickupWorkflow workflow = new PickupWorkflow(
                "warehouse-patrol",
                "Warehouse patrol",
                "minecraft:overworld",
                List.of(new PickupRegionBinding(
                region.id(),
                50,
                Duration.ofSeconds(15)
        )),
                List.of(cache.id()),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_REGION,
                CompletionMode.MANUAL,
                Duration.ofSeconds(300)
        );
        StowlingConfig config = new StowlingConfig(
                2,
                List.of(region),
                List.of(cache),
                List.of(workflow),
                base.storageRoutes(),
                base.packingStations(),
                List.of(),
                base.overflowContainer()
        );
        Path output = tempDir.resolve("cuboid.json");

        loader.write(output, config);

        assertEquals(config, loader.load(output));
    }

    @Test
    void atomicWriteCreatesBackupOfPreviousConfiguration() throws IOException {
        Path path = tempDir.resolve("stowling.json");
        StowlingConfig original = ConfigTestFixtures.config(List.of());
        StowlingConfig updated = ConfigTestFixtures.config(List.of(
                ConfigTestFixtures.route("updated", List.of("minecraft:stone"), 1)
        ));
        loader.write(path, original);

        loader.writeAtomicWithBackup(path, updated);

        assertEquals(updated, loader.load(path));
        assertEquals(original, loader.load(tempDir.resolve("stowling.json.bak")));
        assertTrue(Files.isRegularFile(tempDir.resolve("stowling.json.bak")));
    }

    @Test
    void failedAtomicReplacementLeavesOriginalConfigurationIntact() {
        Path path = tempDir.resolve("stowling.json");
        StowlingConfig original = ConfigTestFixtures.config(List.of());
        StowlingConfig updated = ConfigTestFixtures.config(List.of(
                ConfigTestFixtures.route("updated", List.of("minecraft:stone"), 1)
        ));
        loader.write(path, original);
        ConfigLoader failingLoader = new ConfigLoader((source, target) -> {
            throw new IOException("simulated atomic replacement failure");
        });

        assertThrows(
                ConfigLoadException.class,
                () -> failingLoader.writeAtomicWithBackup(path, updated)
        );

        assertEquals(original, loader.load(path));
    }
}
