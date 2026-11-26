package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.ContainerCacheRefresh;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MinecraftContainerCacheRefreshTest {
    private static final BlockPosition FIRST = new BlockPosition(15, 64, 0);
    private static final BlockPosition PARTNER = new BlockPosition(16, 64, 0);
    private static final BlockPosition BLOCKED = new BlockPosition(17, 64, 0);
    private static final BlockPosition LAST = new BlockPosition(18, 64, 0);
    private static final BlockPosition MARKER = new BlockPosition(64, 64, 0);
    private static final BlockPosition SECOND_STORAGE = new BlockPosition(-10, 64, 0);
    private static final AreaBounds STANDING = new AreaBounds(20, 65, 0, 20, 65, 0);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refreshUsesAnIsolatedReadSessionOnlyWhenEnabledAndReleasesItAfterFailures(boolean enabled) {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, false, enabled);
        refresh.start();
        assertEquals(TaskModuleState.FAILED, finish(refresh).state());
        Inspection used = enabled ? inspection.session : inspection;
        assertEquals(List.of(FIRST, BLOCKED, LAST), used.opened);
        assertEquals(enabled ? 1 : 0, inspection.sessions);
        assertEquals(enabled ? 1 : 0, used.canceled);
        if (enabled) assertTrue(inspection.opened.isEmpty());
    }

    @Test
    void sessionIsReleasedOnSuccessfulCompletion() {
        var world = new World();
        world.loadAll = true;
        world.scanTargets = List.of(new ContainerTarget(FIRST, "minecraft:chest"));
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, false, true);
        refresh.start();
        assertEquals(TaskModuleState.SUCCEEDED, finish(refresh).state());
        assertEquals(List.of(FIRST), inspection.session.opened);
        assertEquals(1, inspection.session.canceled);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopReleasesSessionDuringAndBetweenInspections(boolean between) {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, false, true);
        refresh.start();
        refresh.tick();
        refresh.tick();
        if (between) refresh.tick();
        refresh.requestStop();
        assertEquals(TaskModuleState.SUCCEEDED, refresh.tick().state());
        assertEquals(List.of(FIRST), inspection.session.opened);
        assertEquals(1, inspection.session.canceled);
        refresh.cancel();
        assertEquals(1, inspection.session.canceled);
    }

    @Test
    void disconnectReleasesSessionAndPreventsMoreOpens() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, false, true);
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.tick();
        world.available = false;
        assertEquals(TaskModuleState.FAILED, refresh.tick().state());
        assertEquals(List.of(FIRST), inspection.session.opened);
        assertEquals(1, inspection.session.canceled);
    }

    @Test
    void inspectionExceptionReleasesTheRetainedHandSession() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, false, true);
        refresh.start();
        refresh.tick();
        refresh.tick();
        inspection.session.pollFailure = new IllegalStateException("unsafe hand");
        assertThrows(IllegalStateException.class, refresh::tick);
        assertEquals(1, inspection.session.canceled);
    }

    @Test
    void disabledCategoryExperimentDoesNotScanOrOpenAnyContainer() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = categoryRefresh(world, inspection, new MarkerCache(), Map.of());
        var result = refresh.start();
        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("分类区仅刷新储存容器"));
        assertTrue(world.scanned.isEmpty());
        assertTrue(inspection.opened.isEmpty());
    }

    @Test
    void categoryRefreshOnlyOpensStorageUnionAndUsesTheInsideHalfOfDoubleChests() {
        var world = new World();
        world.loadAll = true;
        world.scanTargets = List.of(new ContainerTarget(MARKER, "minecraft:chest"),
                new ContainerTarget(new BlockPosition(30, 64, 0), "minecraft:chest"),
                new ContainerTarget(FIRST, "minecraft:chest", null, null, PARTNER),
                new ContainerTarget(LAST, "minecraft:barrel"),
                new ContainerTarget(SECOND_STORAGE, "minecraft:chest"));
        var inspection = new Inspection();
        var cache = new MarkerCache();
        var refresh = categoryRefresh(world, inspection, cache,
                Map.of("categoryStorageOnlyEnabled", "true", "scanMarkers", "true",
                        "includeMarkersInInventory", "true"));
        assertEquals(TaskModuleState.RUNNING, refresh.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, finish(refresh).state());
        assertEquals(List.of(PARTNER, LAST, SECOND_STORAGE), inspection.opened);
        assertEquals(STANDING, inspection.targets.getFirst().standingArea());
        assertEquals(List.of(16, -10), world.scanned.stream()
                .map(RegionDefinition::firstX).toList());
        assertEquals(0, cache.writes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void internalMarkerRefreshIsUnaffectedByStorageExperiment(boolean enabled) {
        var world = new World();
        world.loadAll = true;
        world.scanTargets = List.of(new ContainerTarget(MARKER, "minecraft:chest"));
        var inspection = new Inspection();
        var cache = new MarkerCache();
        var refresh = categoryRefresh(world, inspection, cache,
                Map.of("categoryStorageOnlyEnabled", Boolean.toString(enabled),
                        "markerOnly", "true", "reuseSafeHandEnabled", "true"));
        assertEquals(TaskModuleState.RUNNING, refresh.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, finish(refresh).state());
        assertEquals(List.of(MARKER), inspection.opened);
        assertEquals(1, cache.writes);
        assertEquals(0, inspection.sessions);
    }

    @Test
    void regionRefreshStillOpensTheSameContainersWithExperimentEnabled() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, true);
        refresh.start();
        assertEquals(TaskModuleState.FAILED, finish(refresh).state());
        assertEquals(List.of(FIRST, BLOCKED, LAST), inspection.opened);
    }

    private static TaskModuleResult finish(MinecraftContainerCacheRefresh refresh) {
        for (int tick = 0; tick < 50; tick++) {
            var result = refresh.tick();
            if (result.state() == TaskModuleState.SUCCEEDED
                    || result.state() == TaskModuleState.FAILED) return result;
        }
        throw new AssertionError("Refresh did not finish");
    }

    private static MinecraftContainerCacheRefresh categoryRefresh(World world,
            Inspection inspection, MarkerCache cache, Map<String, String> parameters) {
        UUID zoneId = UUID.randomUUID();
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(64, 64, 0, 64, 64, 0),
                new AreaBounds(16, 64, 0, 18, 64, 0), ContainerContentsCategoryRule.ID, List.of(),
                List.of(), STANDING);
        var second = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(80, 64, 0, 80, 64, 0),
                new AreaBounds(-10, 64, 0, -9, 64, 0), "DIRECT_BLOCK_ID", List.of());
        var zone = new CategoryZoneDefinition(zoneId, "Storage", List.of(binding, second));
        var ports = TaskModuleRuntimeRegistry.unavailablePorts(world);
        var context = new TaskExecutionContext(world, ports.droppedItems(),
                ports.containerRegions(), ports.containerTransfers(), ports.containerWithdrawals(),
                inspection, cache, ports.categoryZones(), Map.of(), Map.of(), Map.of(zoneId, zone),
                new TaskRunWorkspace());
        var values = new java.util.HashMap<>(parameters);
        values.put("targetRef", "category-zone:" + zoneId);
        return new MinecraftContainerCacheRefresh(context, ContainerCacheRefresh.from(values), world);
    }

    @Test
    void loadsTheNextChunkDeduplicatesDoubleChestsAndContinuesAfterFailedOpen() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection);
        assertEquals(TaskModuleState.RUNNING, refresh.start().state());
        TaskModuleResult outcome = null;
        for (int i = 0; i < 20; i++) {
            outcome = refresh.tick();
            if (outcome.state() == TaskModuleState.FAILED) {
                break;
            }
        }
        assertEquals(TaskModuleState.FAILED, outcome.state());
        assertTrue(outcome.message().contains("已刷新 2 个容器"));
        assertTrue(outcome.message().contains("1 处失败"));
        assertEquals(List.of(FIRST, BLOCKED, LAST), inspection.opened);
        assertEquals(List.of(new BlockPosition(24, 64, 8)), world.travel);
        assertEquals(1, world.stopCount);
    }

    @Test
    void cancelStopsTheInspectionAndPreventsTheNextOpen() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection);
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.requestStop();
        assertFalse(refresh.safeToStop());
        assertEquals(0, inspection.canceled);

        assertEquals(TaskModuleState.SUCCEEDED, refresh.tick().state());
        assertTrue(refresh.safeToStop());
        assertEquals(1, inspection.canceled);
        assertEquals(List.of(FIRST), inspection.opened);
    }

    @Test
    void splitsAtNegativeAndPositiveChunkEdgesWithoutExtendingTheSelection() {
        var area = new AreaBounds(16, 70, 0, -1, 64, 0);
        var chunks = ContainerCacheRefreshPlan.chunks(UUID.randomUUID(), "Test", area);
        assertEquals(List.of(-1, 0, 16), chunks.stream().map(RegionDefinition::firstX).toList());
        assertEquals(List.of(-1, 15, 16), chunks.stream().map(RegionDefinition::secondX).toList());
    }

    private static MinecraftContainerCacheRefresh refresh(World world, Inspection inspection) {
        return refresh(world, inspection, false);
    }

    private static MinecraftContainerCacheRefresh refresh(World world, Inspection inspection,
            boolean enabled) {
        return refresh(world, inspection, enabled, false);
    }

    private static MinecraftContainerCacheRefresh refresh(World world, Inspection inspection,
            boolean enabled, boolean reuseHand) {
        UUID regionId = UUID.randomUUID();
        var region = new RegionDefinition(regionId, "Test", 15, 64, 0, 18, 64, 0);
        var ports = TaskModuleRuntimeRegistry.unavailablePorts(world);
        var context = new TaskExecutionContext(world, ports.droppedItems(),
                ports.containerRegions(), ports.containerTransfers(), ports.containerWithdrawals(),
                inspection, CategoryMarkerCachePort.UNAVAILABLE, ports.categoryZones(), Map.of(),
                Map.of(regionId, region), Map.of(), new TaskRunWorkspace());
        var settings = ContainerCacheRefresh.from(Map.of("targetRef", "region:" + regionId,
                "categoryStorageOnlyEnabled", Boolean.toString(enabled),
                "reuseSafeHandEnabled", Boolean.toString(reuseHand)));
        return new MinecraftContainerCacheRefresh(context, settings, world);
    }

    private static final class World
            implements MinecraftContainerCacheRefresh.RefreshWorld, NavigationPort {
        private final List<BlockPosition> travel = new ArrayList<>();
        private final List<RegionDefinition> scanned = new ArrayList<>();
        private List<ContainerTarget> scanTargets;
        private boolean loadAll;
        private boolean available = true;
        private int stopCount;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public BlockPosition position() {
            return FIRST;
        }

        @Override
        public String scope() {
            return "test";
        }

        @Override
        public boolean chunkLoaded(BlockPosition center) {
            return loadAll || center.x() < 16 || !travel.isEmpty();
        }

        @Override
        public ContainerScanResult scan(RegionDefinition chunk, ContainerCacheRefresh options) {
            scanned.add(chunk);
            if (scanTargets != null) return ContainerScanResult.success(scanTargets);
            var chest = new ContainerTarget(FIRST, "minecraft:chest", null, null, PARTNER);
            return ContainerScanResult.success(chunk.firstX() < 16 ? List.of(chest)
                    : List.of(chest, new ContainerTarget(BLOCKED, "minecraft:chest"),
                            new ContainerTarget(LAST, "minecraft:barrel")));
        }

        @Override
        public Object memoryStamp(ContainerTarget target) {
            return null;
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            travel.add(target);
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
            stopCount++;
        }
    }

    private static final class Inspection implements ContainerInspectionPort {
        private final List<BlockPosition> opened = new ArrayList<>();
        private final List<ContainerTarget> targets = new ArrayList<>();
        private int canceled;
        private int sessions;
        private Inspection session;
        private RuntimeException pollFailure;

        @Override
        public ContainerInspectionPort openReadSession() {
            sessions++;
            session = new Inspection();
            return session;
        }

        @Override
        public ContainerInspectionResult startInspection(ContainerTarget target) {
            opened.add(target.position());
            targets.add(target);
            return ContainerInspectionResult.pending("pending");
        }

        @Override
        public ContainerInspectionResult pollInspection() {
            if (pollFailure != null) throw pollFailure;
            return BLOCKED.equals(opened.getLast())
                    ? ContainerInspectionResult.failed("cannot open")
                    : ContainerInspectionResult.success("empty", Set.of());
        }

        @Override
        public void cancelInspection() {
            canceled++;
        }
    }

    private static final class MarkerCache implements CategoryMarkerCachePort {
        private int writes;

        @Override
        public Optional<Map<BlockPosition, Set<String>>> contents(UUID bindingId) {
            return Optional.empty();
        }

        @Override
        public void replaceBindings(Map<UUID, Map<BlockPosition, Set<String>>> contents) {
            writes++;
        }
    }
}
