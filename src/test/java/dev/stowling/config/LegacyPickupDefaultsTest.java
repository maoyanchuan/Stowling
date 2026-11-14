package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.AuditMode;
import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.TriggerKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyPickupDefaultsTest {
    private static final String DIMENSION = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @Test
    void removesAllExactNorthDefaultsWithoutTasksAndKeepsSchemaFive() {
        StowlingConfig source = northFixture().config();
        assertValid(source);

        StowlingConfig cleaned = LegacyPickupDefaults.remove(source);

        assertEquals(StowlingConfig.CURRENT_SCHEMA_VERSION, cleaned.schemaVersion());
        assertTrue(cleaned.coordinatePoints().isEmpty());
        assertTrue(cleaned.pickupRegions().isEmpty());
        assertTrue(cleaned.cacheContainers().isEmpty());
        assertTrue(cleaned.pickupWorkflows().isEmpty());
        assertTrue(cleaned.auditSchedules().isEmpty());
        assertEquals(source.overflowContainer(), cleaned.overflowContainer());
        assertEquals(5, source.coordinatePoints().size());
        assertEquals(4, source.pickupWorkflows().size());
        assertValid(cleaned);
    }

    @Test
    void retiresWorkflowsByExactIdRegionOrCacheIndependently() {
        Fixture fixture = northFixture();
        fixture.addUserResources();
        fixture.pickups.clear();
        PickupWorkflow retained = pickup("user-pickup", "user-region", "user-cache");
        fixture.pickups.addAll(List.of(
                pickup("farm-patrol", "user-region", "user-cache"),
                pickup("north-farm-workflow", "user-region", "user-cache"),
                pickup("region-dependent", "north-farm", "user-cache"),
                pickup("cache-dependent", "user-region", "north-cache"), retained));
        StowlingConfig source = fixture.config();
        assertValid(source);

        StowlingConfig cleaned = LegacyPickupDefaults.remove(source);

        assertEquals(List.of(retained), cleaned.pickupWorkflows());
        assertSame(retained, cleaned.pickupWorkflows().getFirst());
        assertValid(cleaned);
    }

    @Test
    void cleanupIsIdempotentAndEmptyConfigurationRemainsValid() {
        StowlingConfig cleaned = LegacyPickupDefaults.remove(northFixture().config());
        StowlingConfig empty = new Fixture().config();

        assertSame(cleaned, LegacyPickupDefaults.remove(cleaned));
        assertSame(empty, LegacyPickupDefaults.remove(empty));
        assertValid(empty);
    }

    @Test
    void preservesUnrelatedNorthNamedUserResourcesTasksAndStorage() {
        Fixture fixture = northFixture();
        fixture.addUserResources();
        AutomationWorkflow task = new AutomationWorkflow("user-task", "User task", List.of(
                new AutomationStep("go", AutomationStepType.NAVIGATE,
                        Map.of("coordinateId", "user-center")),
                new AutomationStep("pickup", AutomationStepType.RUN_PICKUP,
                        Map.of("workflowId", "user-pickup"))
        ));
        fixture.tasks.add(task);
        fixture.routes.add(new StorageRoute("user-route", List.of("minecraft:stone"),
                List.of(), 1, container("user-bulk", 60), null, null));
        fixture.stations.add(new PackingStation("user-station", DIMENSION,
                new BlockPosition(70, 64, 0), container("user-product", 72),
                PackingTrigger.ON_ITEM_COUNT, 64));
        StowlingConfig source = fixture.config();
        assertValid(source);

        StowlingConfig cleaned = LegacyPickupDefaults.remove(source);

        assertEquals(List.of("user-standby", "user-center", "user-cache-point",
                "north-user-mark"), coordinateIds(cleaned));
        assertEquals(List.of("user-region"), cleaned.pickupRegions().stream()
                .map(PickupRegion::id).toList());
        assertEquals(List.of("user-cache"), cleaned.cacheContainers().stream()
                .map(NamedCache::id).toList());
        assertEquals(List.of("user-pickup"), cleaned.pickupWorkflows().stream()
                .map(PickupWorkflow::id).toList());
        assertSame(task, cleaned.automationWorkflows().getFirst());
        assertEquals(source.storageRoutes(), cleaned.storageRoutes());
        assertEquals(source.packingStations(), cleaned.packingStations());
        assertSame(source.overflowContainer(), cleaned.overflowContainer());
        assertValid(cleaned);
    }

    @Test
    void removesNorthCacheFromOtherAuditsAndPreservesTheirRemainingConfiguration() {
        Fixture fixture = northFixture();
        fixture.addUserResources();
        fixture.audits.clear();
        AuditSchedule mixed = new AuditSchedule("mixed-audit", AuditMode.SCHEDULED,
                Duration.ofMinutes(7), List.of("north-cache", "user-cache", "overflow"));
        AuditSchedule user = new AuditSchedule("user-audit", AuditMode.MANUAL,
                Duration.ZERO, List.of("user-cache"));
        AuditSchedule empty = new AuditSchedule("empty-user-audit", AuditMode.MANUAL,
                Duration.ZERO, List.of());
        fixture.audits.addAll(List.of(mixed, empty, user,
                new AuditSchedule("north-only", AuditMode.MANUAL,
                        Duration.ZERO, List.of("north-cache")),
                new AuditSchedule("manual-check", AuditMode.MANUAL,
                        Duration.ZERO, List.of("user-cache"))));

        StowlingConfig cleaned = LegacyPickupDefaults.remove(fixture.config());

        assertEquals(List.of(new AuditSchedule(mixed.id(), mixed.mode(), mixed.interval(),
                List.of("user-cache", "overflow")), empty, user), cleaned.auditSchedules());
        assertSame(empty, cleaned.auditSchedules().get(1));
        assertSame(user, cleaned.auditSchedules().getLast());
        assertValid(cleaned);
    }

    @Test
    void preservesActualUserShapeAndCacheReferencesToCandidatePoints() {
        Fixture fixture = northFixture();
        NamedCoordinate center = fixture.points.get(1);
        NamedCoordinate cachePoint = fixture.points.get(2);
        PickupRegion userRegion = region("user-region", center, center);
        NamedCache userCache = cache("user-cache", cachePoint);
        NamedCoordinate first = fixture.points.get(3);
        NamedCoordinate second = fixture.points.get(4);
        PickupRegion userCuboid = new PickupRegion("user-cuboid", "User cuboid", DIMENSION,
                first.id(), first.position(), CuboidPickupShape.fromCoordinates(first, second));
        fixture.regions.addAll(List.of(userRegion, userCuboid));
        fixture.caches.add(userCache);

        StowlingConfig cleaned = LegacyPickupDefaults.remove(fixture.config());

        assertEquals(List.of("north-farm-center", "north-cache-position",
                "north-farm-first-corner", "north-farm-second-corner"), coordinateIds(cleaned));
        assertSame(userRegion, cleaned.pickupRegions().getFirst());
        assertSame(userCuboid, cleaned.pickupRegions().getLast());
        assertSame(userCache, cleaned.cacheContainers().getFirst());
        assertTrue(cleaned.pickupWorkflows().isEmpty());
        assertValid(cleaned);
    }

    @Test
    void reanchorsSphereWhenDefaultPointIsOnlyTheLegacyStandby() {
        Fixture fixture = northFixture();
        NamedCoordinate center = point("user-center", 100);
        fixture.points.add(center);
        PickupRegion userRegion = region("user-region", fixture.points.getFirst(), center);
        fixture.regions.add(userRegion);

        StowlingConfig cleaned = LegacyPickupDefaults.remove(fixture.config());
        PickupRegion anchored = cleaned.pickupRegions().getFirst();

        assertEquals(List.of("user-center"), coordinateIds(cleaned));
        assertEquals(userRegion.id(), anchored.id());
        assertEquals(userRegion.name(), anchored.name());
        assertEquals(userRegion.dimension(), anchored.dimension());
        assertEquals(center.id(), anchored.standbyCoordinateId());
        assertEquals(center.position(), anchored.standbyPosition());
        assertSame(userRegion.shape(), anchored.shape());
        assertValid(cleaned);
    }

    @Test
    void reanchorsCuboidToFirstNamedCornerWithoutChangingItsShape() {
        Fixture fixture = northFixture();
        NamedCoordinate first = point("user-first", 100);
        NamedCoordinate second = point("user-second", 120);
        fixture.points.addAll(List.of(first, second));
        PickupRegion userRegion = new PickupRegion("user-region", "User region", DIMENSION,
                "north-farm-standby", fixture.points.getFirst().position(),
                CuboidPickupShape.fromCoordinates(first, second));
        fixture.regions.add(userRegion);

        StowlingConfig cleaned = LegacyPickupDefaults.remove(fixture.config());
        PickupRegion anchored = cleaned.pickupRegions().getFirst();

        assertEquals(List.of("user-first", "user-second"), coordinateIds(cleaned));
        assertEquals(first.id(), anchored.standbyCoordinateId());
        assertEquals(first.position(), anchored.standbyPosition());
        assertEquals(userRegion.id(), anchored.id());
        assertEquals(userRegion.name(), anchored.name());
        assertEquals(userRegion.dimension(), anchored.dimension());
        assertSame(userRegion.shape(), anchored.shape());
        assertValid(cleaned);
    }

    @Test
    void skipsCleanupForCustomRegionOrRetiringWorkflowReferences() {
        List<Map<String, String>> references = List.of(
                Map.of("regionId", "north-farm", "idleSeconds", "2",
                        "waitForItems", "false"),
                Map.of("workflowId", "farm-patrol"),
                Map.of("workflowId", "north-farm-workflow"),
                Map.of("workflowId", "renamed-example")
        );
        for (Map<String, String> parameters : references) {
            assertTaskProtectsDefaults(new AutomationStep("pickup",
                    AutomationStepType.RUN_PICKUP, parameters));
        }
    }

    @Test
    void skipsCleanupForEveryCandidateCoordinateUsedByCustomNavigation() {
        for (NamedCoordinate point : northFixture().points) {
            assertTaskProtectsDefaults(new AutomationStep("go", AutomationStepType.NAVIGATE,
                    Map.of("coordinateId", point.id())));
        }
    }

    @Test
    void skipsCleanupForExplicitAndLegacyContainerSteps() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS)) {
            assertTaskProtectsDefaults(new AutomationStep("items", type,
                    Map.of("coordinateId", "north-cache-position",
                            "itemId", "minecraft:stone", "count", "1")));
            assertTaskProtectsDefaults(new AutomationStep("items", type,
                    Map.of("containerId", "north-cache",
                            "itemId", "minecraft:stone", "count", "1")));
        }
        for (String key : List.of("sourceCoordinateId", "destinationCoordinateId")) {
            Map<String, String> parameters = new HashMap<>(Map.of(
                    "sourceCoordinateId", "user-cache-point",
                    "destinationCoordinateId", "user-center",
                    "itemId", "minecraft:stone", "count", "1"));
            parameters.put(key, "north-cache-position");
            assertTaskProtectsDefaults(new AutomationStep("transfer",
                    AutomationStepType.TRANSFER_ITEMS, parameters));
        }
        assertTaskProtectsDefaults(new AutomationStep("transfer",
                AutomationStepType.TRANSFER_ITEMS,
                Map.of("sourceContainerId", "north-cache", "destination", "user-cache",
                        "itemId", "minecraft:stone", "count", "1")));
        assertTaskProtectsDefaults(new AutomationStep("transfer",
                AutomationStepType.TRANSFER_ITEMS,
                Map.of("sourceContainerId", "user-cache", "destination", "north-cache",
                        "itemId", "minecraft:stone", "count", "1")));
    }

    @Test
    void skipsCleanupForDirectPickupAndEveryPackingCoordinateRole() {
        assertTaskProtectsDefaults(new AutomationStep("pickup", AutomationStepType.RUN_PICKUP,
                Map.of("standbyCoordinateId", "user-standby",
                        "firstCornerCoordinateId", "user-center",
                        "secondCornerCoordinateId", "user-cache-point",
                        "cacheIds", "user-cache,north-cache",
                        "idleSeconds", "2", "waitForItems", "false")));
        for (String key : List.of("standbyCoordinateId", "firstCornerCoordinateId",
                "secondCornerCoordinateId")) {
            Map<String, String> parameters = new HashMap<>(Map.of(
                    "standbyCoordinateId", "user-standby",
                    "firstCornerCoordinateId", "user-center",
                    "secondCornerCoordinateId", "user-cache-point",
                    "cacheIds", "user-cache", "idleSeconds", "2", "waitForItems", "false"));
            parameters.put(key, "north-farm-standby");
            assertTaskProtectsDefaults(new AutomationStep("pickup",
                    AutomationStepType.RUN_PICKUP, parameters));
        }
        for (String key : List.of("sourceCoordinateId", "boxCoordinateId",
                "outputCoordinateId", "fallbackCoordinateId")) {
            Map<String, String> parameters = new HashMap<>(Map.of(
                    "sourceCoordinateId", "user-cache-point",
                    "boxCoordinateId", "user-standby",
                    "outputCoordinateId", "user-center",
                    "fallbackCoordinateId", "north-user-mark",
                    "itemId", "minecraft:stone", "count", "1",
                    "completionTrigger", "ON_ITEM_COUNT", "completionThreshold", "64"));
            parameters.put(key, "north-cache-position");
            assertTaskProtectsDefaults(new AutomationStep("pack",
                    AutomationStepType.PACK_ITEMS, parameters));
        }
    }

    @Test
    void skipsCleanupForCustomInlineAndTriggerDependencies() {
        TaskSettings inline = new TaskSettings(TaskPriority.MEDIUM, new TaskTrigger(), false,
                new TaskInterruption(InterruptionKind.INLINE, List.of(
                        new AutomationStep("go", AutomationStepType.NAVIGATE,
                                Map.of("coordinateId", "north-farm-standby"))), null));
        TaskSettings trigger = new TaskSettings(TaskPriority.MEDIUM,
                new TaskTrigger(TriggerKind.ITEMS_PRESENT, 0L, List.of(),
                        TaskTrigger.DEFAULT_ZONE_ID, List.of("north-farm")),
                false, TaskInterruption.none());
        for (TaskSettings settings : List.of(inline, trigger)) {
            Fixture fixture = northFixture();
            fixture.tasks.add(new AutomationWorkflow("user-task", "User task",
                    List.of(waitStep()), settings));
            StowlingConfig source = fixture.config();
            assertValid(source);

            assertSame(source, LegacyPickupDefaults.remove(source));
        }
    }

    @Test
    void protectsDependenciesInSurvivingPickupTaskSettings() {
        Fixture fixture = northFixture();
        fixture.addUserResources();
        PickupWorkflow user = fixture.pickups.removeLast();
        TaskSettings settings = new TaskSettings(TaskPriority.MEDIUM,
                new TaskTrigger(TriggerKind.ITEMS_PRESENT, 0L, List.of(),
                        TaskTrigger.DEFAULT_ZONE_ID, List.of("north-farm")),
                false, TaskInterruption.none());
        fixture.pickups.add(new PickupWorkflow(user.id(), user.name(), user.dimension(),
                user.regions(), user.cacheIds(), user.cachePolicy(), user.classificationTiming(),
                user.completionMode(), user.completionIdleDelay(), settings));
        StowlingConfig source = fixture.config();
        assertValid(source);

        assertSame(source, LegacyPickupDefaults.remove(source));
    }

    @Test
    void persistsCleanedConfigurationWithoutRevivalAndBacksUpTheOldChain() throws IOException {
        ConfigLoader loader = new ConfigLoader();
        StowlingConfig original = northFixture().config();
        Path path = tempDir.resolve("stowling.json");
        loader.write(path, original);
        String before = Files.readString(path);
        StowlingConfig cleaned = LegacyPickupDefaults.remove(loader.load(path));

        loader.writeAtomicWithBackup(path, cleaned);
        StowlingConfig reloaded = new ConfigLoader().load(path);
        Path backup = tempDir.resolve("stowling.json.bak");

        assertEquals(cleaned, reloaded);
        assertSame(reloaded, LegacyPickupDefaults.remove(reloaded));
        assertEquals(before, Files.readString(backup));
        assertEquals(original, loader.load(backup));
        assertTrue(reloaded.coordinatePoints().isEmpty());
        assertTrue(reloaded.pickupRegions().isEmpty());
        assertTrue(reloaded.cacheContainers().isEmpty());
        assertTrue(reloaded.pickupWorkflows().isEmpty());
        assertFalse(Files.readString(path).contains("north-farm"));
        assertValid(reloaded);
    }

    private static void assertTaskProtectsDefaults(AutomationStep step) {
        Fixture fixture = northFixture();
        fixture.addUserResources();
        fixture.tasks.add(new AutomationWorkflow("user-task", "User task", List.of(step)));
        StowlingConfig source = fixture.config();
        assertValid(source);
        assertSame(source, LegacyPickupDefaults.remove(source), step.toString());
    }

    private static void assertValid(StowlingConfig config) {
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    private static List<String> coordinateIds(StowlingConfig config) {
        return config.coordinatePoints().stream().map(NamedCoordinate::id).toList();
    }

    private static AutomationStep waitStep() {
        return new AutomationStep("wait", AutomationStepType.WAIT, Map.of("seconds", "1"));
    }

    private static Fixture northFixture() {
        Fixture fixture = new Fixture();
        fixture.points.addAll(List.of(point("north-farm-standby", 0),
                point("north-farm-center", 2), point("north-cache-position", 8),
                point("north-farm-first-corner", -4), point("north-farm-second-corner", 4)));
        fixture.regions.add(region("north-farm", fixture.points.get(0), fixture.points.get(1)));
        fixture.caches.add(cache("north-cache", fixture.points.get(2)));
        fixture.pickups.addAll(List.of(pickup("farm-patrol", "north-farm", "north-cache"),
                pickup("north-farm-workflow", "north-farm", "north-cache"),
                pickup("renamed-example", "north-farm", "north-cache"),
                pickup("another-example", "north-farm", "north-cache")));
        fixture.audits.add(new AuditSchedule("manual-check", AuditMode.MANUAL,
                Duration.ZERO, List.of("north-cache")));
        return fixture;
    }

    private static NamedCoordinate point(String id, int x) {
        return new NamedCoordinate(id, id, DIMENSION, new BlockPosition(x, 64, 0));
    }

    private static PickupRegion region(
            String id, NamedCoordinate standby, NamedCoordinate center
    ) {
        return new PickupRegion(id, id, DIMENSION, standby.id(), standby.position(),
                new SpherePickupShape(center.id(), center.position(), 16));
    }

    private static NamedCache cache(String id, NamedCoordinate point) {
        return new NamedCache(id, id, point.id(), DIMENSION, point.position(), ContainerType.CHEST);
    }

    private static PickupWorkflow pickup(String id, String regionId, String cacheId) {
        return new PickupWorkflow(id, id, DIMENSION,
                List.of(new PickupRegionBinding(regionId, 100, Duration.ZERO)), List.of(cacheId),
                CachePolicy.NEAREST_AVAILABLE, ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL, Duration.ZERO);
    }

    private static ContainerRef container(String id, int x) {
        return new ContainerRef(id, DIMENSION, new BlockPosition(x, 64, 0), ContainerType.CHEST);
    }

    private static final class Fixture {
        private final List<NamedCoordinate> points = new ArrayList<>();
        private final List<PickupRegion> regions = new ArrayList<>();
        private final List<NamedCache> caches = new ArrayList<>();
        private final List<PickupWorkflow> pickups = new ArrayList<>();
        private final List<AutomationWorkflow> tasks = new ArrayList<>();
        private final List<StorageRoute> routes = new ArrayList<>();
        private final List<PackingStation> stations = new ArrayList<>();
        private final List<AuditSchedule> audits = new ArrayList<>();

        private void addUserResources() {
            NamedCoordinate standby = point("user-standby", 100);
            NamedCoordinate center = point("user-center", 102);
            NamedCoordinate cachePoint = point("user-cache-point", 108);
            points.addAll(List.of(standby, center, cachePoint, point("north-user-mark", 110)));
            regions.add(region("user-region", standby, center));
            caches.add(cache("user-cache", cachePoint));
            pickups.add(pickup("user-pickup", "user-region", "user-cache"));
        }

        private StowlingConfig config() {
            return new StowlingConfig(5, points, regions, caches, pickups, tasks, routes,
                    stations, audits, container("overflow", 200));
        }
    }
}
