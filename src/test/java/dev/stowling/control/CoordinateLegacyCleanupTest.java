package dev.stowling.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.ConfigLoader;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.StowlingConfig;
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
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventorySource;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StowlingDatabase;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskScheduleInfo;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.TriggerKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinateLegacyCleanupTest {
    private static final String DIMENSION = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @Test
    void confirmedCacheDeletionRemovesWholeAffectedPickupsButKeepsUnrelatedResources()
            throws IOException {
        StowlingConfig original = config(List.of(keepAutomation()));
        Fixture fixture = fixture("cache-cleanup", original);
        ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();
        byte[] originalBytes = Files.readAllBytes(fixture.path());

        ConfigCoordinatePointsSnapshot saved = fixture.service().updateCoordinatePoints(
                removal(before, List.of("cache-position"), List.of("cache-position"))
        );

        StowlingConfig disk = fixture.store().read().config();
        assertEquals(Set.of("farm", "keep-region"), regionIds(disk));
        assertEquals(List.of("keep-cache"), disk.cacheContainers().stream()
                .map(NamedCache::id).toList());
        assertEquals(List.of("keep-workflow"), workflowIds(disk));
        assertEquals(original.automationWorkflows(), disk.automationWorkflows());
        assertEquals(original.storageRoutes(), disk.storageRoutes());
        assertEquals(original.packingStations(), disk.packingStations());
        assertEquals(original.auditSchedules(), disk.auditSchedules());
        assertEquals(original.overflowContainer(), disk.overflowContainer());
        assertEquals(original.pickupRegions(), disk.pickupRegions());
        assertFalse(disk.coordinatePoints().stream()
                .anyMatch(point -> point.id().equals("cache-position")));
        assertEquals(original.coordinatePoints().stream()
                .filter(point -> !point.id().equals("cache-position")).toList(),
                disk.coordinatePoints());
        assertNotEquals(before.revision(), saved.revision());
        assertTrue(saved.revision().matches("[0-9a-f]{64}"));
        assertEquals(saved.revision(), fixture.store().read().revision());
        assertEquals(saved, fixture.service().coordinatePoints());
        assertEquals(disk, fixture.activated().get());
        assertArrayEquals(originalBytes, Files.readAllBytes(backup(fixture.path())));

        byte[] savedBytes = Files.readAllBytes(fixture.path());
        assertThrows(ConfigRevisionConflictException.class, () ->
                fixture.service().updateCoordinatePoints(new ConfigCoordinatePointsUpdateRequest(
                        before.revision(), saved.coordinatePoints(), List.of()
                )));
        assertArrayEquals(savedBytes, Files.readAllBytes(fixture.path()));
        assertArrayEquals(originalBytes, Files.readAllBytes(backup(fixture.path())));
    }

    @Test
    void confirmedStandbyAndShapeDeletionRemovesOnlyTheirRegionsAndWholeAffectedPickups() {
        for (String id : List.of("standby", "first-corner", "second-corner", "keep-center")) {
            Fixture fixture = fixture("region-" + id, config(List.of(keepAutomation())));
            StowlingConfig original = fixture.store().read().config();
            ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

            fixture.service().updateCoordinatePoints(removal(before, List.of(id), List.of(id)));

            StowlingConfig disk = fixture.store().read().config();
            boolean farmRemoved = !id.equals("keep-center");
            assertEquals(Set.of(farmRemoved ? "keep-region" : "farm"), regionIds(disk));
            assertEquals(List.of(farmRemoved ? "keep-workflow" : "farm-workflow"),
                    workflowIds(disk));
            assertEquals(original.cacheContainers(), disk.cacheContainers());
            assertEquals(original.automationWorkflows(), disk.automationWorkflows());
            assertEquals(original.storageRoutes(), disk.storageRoutes());
            assertEquals(original.auditSchedules(), disk.auditSchedules());
            assertEquals(disk, fixture.activated().get());
        }
    }

    @Test
    void legacyTwoArgumentRequestAndUnconfirmedDeletionRemainBlocked() throws IOException {
        for (boolean legacy : List.of(true, false)) {
            Fixture fixture = fixture("unconfirmed-" + legacy, config(List.of(keepAutomation())));
            ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();
            List<ConfigCoordinatePointInfo> remaining = remaining(
                    before, List.of("cache-position")
            );
            ConfigCoordinatePointsUpdateRequest request = legacy
                    ? new ConfigCoordinatePointsUpdateRequest(before.revision(), remaining)
                    : new ConfigCoordinatePointsUpdateRequest(before.revision(), remaining,
                            List.of());

            assertRejectedUnchanged(fixture, request);
        }
    }

    @Test
    void rejectsUnknownAndStillRetainedCleanupIdsEvenAlongsideAValidConfirmation()
            throws IOException {
        List<List<String>> confirmations = List.of(
                List.of("missing"), List.of("keep-cache-position"),
                List.of("cache-position", "missing"),
                List.of("cache-position", "keep-cache-position")
        );
        for (int index = 0; index < confirmations.size(); index++) {
            Fixture fixture = fixture("invalid-confirmation-" + index,
                    config(List.of(keepAutomation())));
            ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

            assertRejectedUnchanged(fixture,
                    removal(before, List.of("cache-position"), confirmations.get(index)));
        }
    }

    @Test
    void confirmingOneDeletionDoesNotAuthorizeCleanupOfAnotherDeletedCoordinate()
            throws IOException {
        Fixture fixture = fixture("partial-confirmation", config(List.of(keepAutomation())));
        ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

        assertRejectedUnchanged(fixture, removal(before,
                List.of("cache-position", "standby"), List.of("cache-position")));
    }

    @Test
    void remainingAutomationReferencesBlockCleanupWithoutDeletingTasks()
            throws IOException {
        List<ProtectedAutomation> cases = List.of(
                protectedStep("coordinate", "cache-position", AutomationStepType.NAVIGATE,
                        Map.of("coordinateId", "cache-position")),
                protectedStep("direct-pickup", "cache-position", AutomationStepType.RUN_PICKUP,
                        Map.of("standbyCoordinateId", "standby",
                                "firstCornerCoordinateId", "first-corner",
                                "secondCornerCoordinateId", "second-corner",
                                "cacheIds", "cache", "idleSeconds", "0",
                                "classificationTiming", "AFTER_ROUND")),
                protectedStep("region", "standby", AutomationStepType.RUN_PICKUP,
                        Map.of("regionId", "farm", "idleSeconds", "0")),
                protectedStep("workflow", "cache-position", AutomationStepType.RUN_PICKUP,
                        Map.of("workflowId", "farm-workflow")),
                protectedStep("legacy-cache", "cache-position", AutomationStepType.TAKE_ITEMS,
                        Map.of("containerId", "cache",
                                "itemId", "minecraft:stone", "count", "1"))
        );
        for (ProtectedAutomation protectedAutomation : cases) {
            Fixture fixture = fixture("protected-" + protectedAutomation.name(),
                    config(List.of(keepAutomation(), protectedAutomation.workflow())));
            ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

            assertRejectedUnchanged(fixture, removal(before,
                    List.of(protectedAutomation.coordinateId()),
                    List.of(protectedAutomation.coordinateId())));
        }
    }

    @Test
    void remainingInlineAndTriggerRegionReferencesBlockCleanupWithoutChangingTheFile()
            throws IOException {
        TaskSettings defaults = TaskSettings.defaults();
        AutomationStep regionStep = step(AutomationStepType.RUN_PICKUP,
                Map.of("regionId", "farm", "idleSeconds", "0"));
        TaskSettings inline = new TaskSettings(
                defaults.priority(), defaults.trigger(), false,
                new TaskInterruption(InterruptionKind.INLINE, List.of(regionStep), null)
        );
        TaskSettings trigger = new TaskSettings(
                defaults.priority(), new TaskTrigger(TriggerKind.ITEMS_PRESENT, 0L,
                        List.of(), TaskTrigger.DEFAULT_ZONE_ID, List.of("farm")),
                false, TaskInterruption.none()
        );
        List<TaskSettings> settings = List.of(inline, trigger);
        for (int index = 0; index < settings.size(); index++) {
            AutomationWorkflow protectedWorkflow = new AutomationWorkflow(
                    "protected-task", "Protected task", List.of(waitStep()), settings.get(index)
            );
            Fixture fixture = fixture("protected-settings-" + index,
                    config(List.of(keepAutomation(), protectedWorkflow)));
            ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

            assertRejectedUnchanged(fixture,
                    removal(before, List.of("standby"), List.of("standby")));
        }
    }

    @Test
    void remainingAuditReferencePreventsDeletingItsLegacyCache() throws IOException {
        Fixture fixture = fixture("protected-audit", config(List.of(keepAutomation())));
        ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

        assertRejectedUnchanged(fixture, removal(before,
                List.of("keep-cache-position"), List.of("keep-cache-position")));
    }

    @Test
    void successfulCleanupDisablesRemovedPickupSchedulesAndCancelsTheirPendingRuns() {
        Fixture fixture = fixture("pending-cleanup", config(List.of(keepAutomation())));
        List<TaskKey> removedKeys = List.of(
                new TaskKey(TaskKey.PICKUP, "farm-workflow"),
                new TaskKey(TaskKey.PICKUP, "shared-workflow")
        );
        TaskKey keptKey = new TaskKey(TaskKey.PICKUP, "keep-workflow");
        for (TaskKey key : removedKeys) {
            fixture.service().enableTaskDefinition(key);
        }
        fixture.service().enableTaskDefinition(keptKey);
        TaskInfo farmRun = fixture.service().requestPickup(new PickupRequest("farm-workflow"));
        TaskInfo sharedRun = fixture.service().requestPickup(new PickupRequest("shared-workflow"));
        TaskInfo keptRun = fixture.service().requestPickup(new PickupRequest("keep-workflow"));
        assertEquals("PENDING", farmRun.status());
        assertEquals("PENDING", sharedRun.status());
        TaskScheduleInfo keptBefore = fixture.service().taskScheduling().stream()
                .filter(info -> info.key().equals(keptKey)).findFirst().orElseThrow();
        assertEquals("QUEUED", keptBefore.state());
        ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();

        fixture.service().updateCoordinatePoints(removal(before,
                List.of("cache-position"), List.of("cache-position")));

        for (TaskScheduleInfo info : fixture.service().taskScheduling()) {
            if (removedKeys.contains(info.key())) {
                assertFalse(info.enabled());
                assertNotEquals("QUEUED", info.state());
                assertNull(info.runId());
            }
        }
        assertEquals("CANCELLED", taskStatus(fixture, farmRun.id()));
        assertEquals("CANCELLED", taskStatus(fixture, sharedRun.id()));
        assertEquals("PENDING", taskStatus(fixture, keptRun.id()));
        assertEquals(keptBefore, fixture.service().taskScheduling().stream()
                .filter(info -> info.key().equals(keptKey)).findFirst().orElseThrow());
        assertTrue(fixture.service().taskDefinitions().stream()
                .noneMatch(definition -> removedKeys.contains(definition.key())));
    }

    private static void assertRejectedUnchanged(
            Fixture fixture,
            ConfigCoordinatePointsUpdateRequest request
    ) throws IOException {
        byte[] beforeBytes = Files.readAllBytes(fixture.path());
        var beforeConfig = fixture.store().read();
        ConfigCoordinatePointsSnapshot beforePoints = fixture.service().coordinatePoints();
        List<TaskScheduleInfo> beforeScheduling = fixture.service().taskScheduling();
        List<TaskInfo> beforeTasks = fixture.service().tasks();

        assertThrows(InvalidCoordinatePointsException.class,
                () -> fixture.service().updateCoordinatePoints(request));

        assertArrayEquals(beforeBytes, Files.readAllBytes(fixture.path()));
        assertEquals(beforeConfig, fixture.store().read());
        assertEquals(beforePoints, fixture.service().coordinatePoints());
        assertEquals(beforeScheduling, fixture.service().taskScheduling());
        assertEquals(beforeTasks, fixture.service().tasks());
        assertNull(fixture.activated().get());
        assertFalse(Files.exists(backup(fixture.path())));
    }

    private Fixture fixture(String name, StowlingConfig config) {
        Path path = tempDir.resolve(name + ".json");
        ConfigLoader loader = new ConfigLoader();
        loader.write(path, config);
        ConfigFileStore store = new ConfigFileStore(path, loader);
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve(name + ".db"));
        AtomicReference<StowlingConfig> activated = new AtomicReference<>();
        InventorySource source = new InventorySource() {
            @Override
            public String sourceId() {
                return "test";
            }

            @Override
            public CompletableFuture<InventoryCapture> capture(ContainerRef container) {
                return CompletableFuture.completedFuture(InventoryCapture.fromSlots(
                        "test", container.id(), container.dimension(), Instant.EPOCH,
                        InventoryFreshness.FRESH, List.of()
                ));
            }
        };
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config, new SqliteTaskRepository(database), new SqliteInventoryRepository(database),
                source, ignored -> {
                    throw new AssertionError("Pickup runtime must not start during cleanup");
                }, Runnable::run, () -> config, activated::set, store
        );
        return new Fixture(path, store, service, activated);
    }

    private static StowlingConfig config(List<AutomationWorkflow> automations) {
        NamedCoordinate standby = coordinate("standby", 0);
        NamedCoordinate first = coordinate("first-corner", 1);
        NamedCoordinate second = new NamedCoordinate("second-corner", "Second corner", DIMENSION,
                new BlockPosition(10, 70, 10));
        NamedCoordinate cachePoint = coordinate("cache-position", 20);
        NamedCoordinate keepStandby = coordinate("keep-standby", 40);
        NamedCoordinate keepCenter = coordinate("keep-center", 41);
        NamedCoordinate keepCachePoint = coordinate("keep-cache-position", 50);
        PickupRegion farm = new PickupRegion(
                "farm", "Farm", DIMENSION, standby.id(), standby.position(),
                CuboidPickupShape.fromCoordinates(first, second)
        );
        PickupRegion keep = new PickupRegion(
                "keep-region", "Keep region", DIMENSION, keepStandby.id(), keepStandby.position(),
                new SpherePickupShape(keepCenter.id(), keepCenter.position(), 8)
        );
        NamedCache cache = cache("cache", cachePoint);
        NamedCache keepCache = cache("keep-cache", keepCachePoint);
        List<PickupWorkflow> pickups = List.of(
                pickup("farm-workflow", List.of("farm"), List.of("cache")),
                pickup("keep-workflow", List.of("keep-region"), List.of("keep-cache")),
                pickup("shared-workflow", List.of("farm", "keep-region"),
                        List.of("cache", "keep-cache"))
        );
        StorageRoute route = new StorageRoute("keep-route", List.of("minecraft:stone"),
                List.of(), 1, container("bulk", 60), null, null);
        AuditSchedule audit = new AuditSchedule("keep-audit", AuditMode.MANUAL,
                Duration.ZERO, List.of("keep-cache"));
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(standby, first, second, cachePoint, keepStandby, keepCenter, keepCachePoint),
                List.of(farm, keep), List.of(cache, keepCache), pickups, automations,
                List.of(route), List.of(), List.of(audit), container("overflow", 80)
        );
    }

    private static PickupWorkflow pickup(String id, List<String> regions, List<String> caches) {
        return new PickupWorkflow(
                id, id, DIMENSION,
                regions.stream().map(region -> new PickupRegionBinding(region, 100, Duration.ZERO))
                        .toList(),
                caches, CachePolicy.NEAREST_AVAILABLE, ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL, Duration.ZERO
        );
    }

    private static NamedCoordinate coordinate(String id, int x) {
        return new NamedCoordinate(id, id, DIMENSION, new BlockPosition(x, 64, 0));
    }

    private static NamedCache cache(String id, NamedCoordinate coordinate) {
        return new NamedCache(id, id, coordinate.id(), DIMENSION,
                coordinate.position(), ContainerType.CHEST);
    }

    private static ContainerRef container(String id, int x) {
        return new ContainerRef(id, DIMENSION, new BlockPosition(x, 64, 0), ContainerType.CHEST);
    }

    private static AutomationWorkflow keepAutomation() {
        return new AutomationWorkflow("keep-task", "Keep task", List.of(waitStep()));
    }

    private static AutomationStep waitStep() {
        return step(AutomationStepType.WAIT, Map.of("seconds", "1"));
    }

    private static AutomationStep step(AutomationStepType type, Map<String, String> parameters) {
        return new AutomationStep("action", type, parameters);
    }

    private static ProtectedAutomation protectedStep(
            String name,
            String coordinateId,
            AutomationStepType type,
            Map<String, String> parameters
    ) {
        return new ProtectedAutomation(name, coordinateId, new AutomationWorkflow(
                "protected-task", "Protected task", List.of(step(type, parameters))
        ));
    }

    private static ConfigCoordinatePointsUpdateRequest removal(
            ConfigCoordinatePointsSnapshot before,
            List<String> deletedIds,
            List<String> confirmedIds
    ) {
        return new ConfigCoordinatePointsUpdateRequest(
                before.revision(), remaining(before, deletedIds), confirmedIds
        );
    }

    private static List<ConfigCoordinatePointInfo> remaining(
            ConfigCoordinatePointsSnapshot before,
            List<String> deletedIds
    ) {
        return before.coordinatePoints().stream()
                .filter(point -> !deletedIds.contains(point.id())).toList();
    }

    private static Set<String> regionIds(StowlingConfig config) {
        return Set.copyOf(config.pickupRegions().stream().map(PickupRegion::id).toList());
    }

    private static List<String> workflowIds(StowlingConfig config) {
        return config.pickupWorkflows().stream().map(PickupWorkflow::id).toList();
    }

    private static String taskStatus(Fixture fixture, UUID id) {
        return fixture.service().tasks().stream().filter(task -> task.id().equals(id))
                .findFirst().orElseThrow().status();
    }

    private static Path backup(Path path) {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    private record ProtectedAutomation(
            String name,
            String coordinateId,
            AutomationWorkflow workflow
    ) {
    }

    private record Fixture(
            Path path,
            ConfigFileStore store,
            StowlingControlServiceImpl service,
            AtomicReference<StowlingConfig> activated
    ) {
    }
}
