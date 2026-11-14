package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

        assertEquals(2, config.schemaVersion());
        assertEquals(Duration.ofSeconds(60), config.defaultIdleDelay());
        assertEquals("overflow", config.overflowContainer().id());
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

        assertEquals(2, config.schemaVersion());
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

        Path output = tempDir.resolve("migrated.json");
        loader.write(output, config);
        String written = Files.readString(output);
        assertTrue(written.contains("\"schemaVersion\": 2"));
        assertFalse(written.contains("pickupSites"));
        assertFalse(written.contains("defaultIdleDelay"));
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
