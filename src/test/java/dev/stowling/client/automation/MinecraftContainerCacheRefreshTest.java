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
    void multiAreaRefreshRequiresItsSwitchAndKeepsSelectionOrderWithOverlapDedup(boolean enabled) {
        var world = new World();
        world.loadAll = true;
        var first = new RegionDefinition(UUID.randomUUID(), "First", 18, 64, 0, 18, 64, 0);
        var second = new RegionDefinition(UUID.randomUUID(), "Second", 15, 64, 0, 18, 64, 0);
        var chest = new ContainerTarget(FIRST, "minecraft:chest", null, null, PARTNER);
        var last = new ContainerTarget(LAST, "minecraft:barrel");
        world.regionTargets = Map.of(first.id(), List.of(last), second.id(), List.of(chest, last));
        var inspection = new Inspection();
        var ports = TaskModuleRuntimeRegistry.unavailablePorts(world);
        var context = new TaskExecutionContext(world, ports.droppedItems(),
                ports.containerRegions(), ports.containerTransfers(), ports.containerWithdrawals(),
                inspection, CategoryMarkerCachePort.UNAVAILABLE, ports.categoryZones(), Map.of(),
                Map.of(first.id(), first, second.id(), second), Map.of(), new TaskRunWorkspace());
        var options = ContainerCacheRefresh.from(Map.of("targetRefs",
                new com.google.gson.Gson().toJson(List.of("region:" + first.id(),
                        "region:" + second.id())), "multiAreaEnabled", Boolean.toString(enabled)));
        var refresh = new MinecraftContainerCacheRefresh(context, options, world);
        var started = refresh.start();
        if (!enabled) {
            assertEquals(TaskModuleState.FAILED, started.state());
            assertTrue(world.scanned.isEmpty());
            assertEquals(0, inspection.sessions);
            return;
        }
        assertTrue(refresh.tick().message().contains("选区 1/2「First」"));
        assertEquals(TaskModuleState.SUCCEEDED, finish(refresh).state());
        assertEquals(List.of(LAST, FIRST), inspection.session.opened);
        assertEquals(List.of(first.id(), second.id()), world.scanned.stream()
                .map(RegionDefinition::id).distinct().toList());
    }

    @Test
    void foodPauseWaitsForCacheConfirmationAndResumesRemainingBoxesFromCurrentPosition() {
        var world = new World();
        world.loadAll = true;
        world.memory = "before";
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, Map.of("foodPauseResumeEnabled", "true"));
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.tick();
        var firstSession = inspection.session;
        refresh.requestPauseAtSafeNode();
        refresh.tick();
        assertFalse(refresh.pausedAtSafeNode());
        assertEquals(0, firstSession.canceled);
        world.memory = "confirmed";
        refresh.tick();
        refresh.tick();
        assertTrue(refresh.pausedAtSafeNode());
        assertEquals(List.of(FIRST), firstSession.opened);
        assertEquals(1, firstSession.canceled);
        world.reachable = Set.of(LAST);
        world.memory = null;
        refresh.resumeFromPause();
        refresh.tick();
        assertFalse(refresh.pausedAtSafeNode());
        assertEquals(2, inspection.sessions);
        assertEquals(List.of(LAST), inspection.session.opened);
        assertEquals(TaskModuleState.FAILED, finish(refresh).state());
        assertEquals(List.of(LAST, BLOCKED), inspection.session.opened);
        assertEquals(List.of(FIRST), firstSession.opened);
    }

    @Test
    void pauseDuringApproachReturnsUnopenedTargetToQueueInsteadOfSkippingIt() {
        var world = new World();
        world.loadAll = true;
        var inspection = new Inspection();
        inspection.canPauseBeforeOpen = true;
        var refresh = refresh(world, inspection, Map.of("foodPauseResumeEnabled", "true"));
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.tick();
        refresh.requestPauseAtSafeNode();
        refresh.tick();
        assertTrue(refresh.pausedAtSafeNode());
        refresh.resumeFromPause();
        assertEquals(TaskModuleState.FAILED, finish(refresh).state());
        assertEquals(List.of(FIRST, BLOCKED, LAST), inspection.session.opened);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pauseHookIsInactiveWhenDisabledAndExplicitStopNeverResumes(boolean enabled) {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection,
                Map.of("foodPauseResumeEnabled", Boolean.toString(enabled)));
        refresh.start();
        refresh.tick();
        refresh.requestPauseAtSafeNode();
        refresh.tick();
        assertEquals(enabled, refresh.pausedAtSafeNode());
        refresh.cancel();
        assertEquals(TaskModuleState.SUCCEEDED, refresh.tick().state());
        assertEquals(enabled ? 0 : 1, inspection.sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "invalid", ""})
    void baselineCollectsLoadedSlicesAndUsesOneSessionRegardlessOfRetiredFlags(String legacy) {
        var world = new World();
        world.loadAll = true;
        world.reachable = Set.of(LAST);
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, Map.of("reuseSafeHandEnabled", legacy,
                "nearbyRouteEnabled", legacy));
        refresh.start();
        refresh.tick();
        refresh.tick();
        assertEquals(2, world.scanned.size());
        assertTrue(inspection.opened.isEmpty());
        assertEquals(TaskModuleState.FAILED, finish(refresh).state());
        assertEquals(List.of(LAST, FIRST, BLOCKED), inspection.session.opened);
        assertEquals(1, inspection.sessions);
        assertEquals(1, inspection.session.canceled);
    }

    @Test
    void nearbyRouteUsesDoubleChestPartnerAndStandingAreaDistance() {
        var far = new ContainerTarget(new BlockPosition(100, 64, 0), "minecraft:chest",
                STANDING, null, PARTNER);
        var near = new ContainerTarget(LAST, "minecraft:chest",
                new AreaBounds(100, 64, 0, 100, 64, 0));
        assertEquals(far, ContainerCacheRefreshPlan.next(List.of(near, far), FIRST,
                PARTNER::equals));
        assertEquals(far, ContainerCacheRefreshPlan.next(List.of(near, far), FIRST,
                point -> false));
    }

    @Test
    void nearbyRouteReevaluatesReachAfterEachRead() {
        var world = new World();
        world.loadAll = true;
        var inspection = new Inspection();
        var refresh = refresh(world, inspection);
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.tick();
        assertEquals(List.of(FIRST), inspection.session.opened);
        refresh.tick();
        world.reachable = Set.of(LAST);
        refresh.tick();
        assertEquals(List.of(FIRST, LAST), inspection.session.opened);
        refresh.requestStop();
        refresh.tick();
        assertEquals(1, inspection.session.canceled);
        assertTrue(world.travel.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nearbyRouteSessionClosesOnStopOrDisconnectWithoutOpeningMore(boolean disconnect) {
        var world = new World();
        world.loadAll = true;
        var inspection = new Inspection();
        var refresh = refresh(world, inspection);
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.tick();
        if (disconnect) world.available = false;
        else refresh.requestStop();
        assertEquals(disconnect ? TaskModuleState.FAILED : TaskModuleState.SUCCEEDED,
                refresh.tick().state());
        assertEquals(List.of(FIRST), inspection.session.opened);
        assertEquals(1, inspection.session.canceled);
        refresh.cancel();
        assertEquals(1, inspection.session.canceled);
    }

    @Test
    void nearbyRouteWaitsForTheCacheBeforeOpeningAnotherContainer() {
        var world = new World();
        world.loadAll = true;
        world.memory = "before";
        var inspection = new Inspection();
        var refresh = refresh(world, inspection);
        refresh.start();
        refresh.tick();
        refresh.tick();
        refresh.tick();
        assertEquals(TaskModuleState.WAITING, refresh.tick().state());
        assertEquals(TaskModuleState.WAITING, refresh.tick().state());
        assertEquals(List.of(FIRST), inspection.session.opened);
        world.memory = "after";
        refresh.tick();
        refresh.tick();
        assertEquals(List.of(FIRST, BLOCKED), inspection.session.opened);
        refresh.cancel();
    }

    @Test
    void sessionIsReleasedOnSuccessfulCompletion() {
        var world = new World();
        world.loadAll = true;
        world.scanTargets = List.of(new ContainerTarget(FIRST, "minecraft:chest"));
        var inspection = new Inspection();
        var refresh = refresh(world, inspection);
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
        var refresh = refresh(world, inspection);
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
        var refresh = refresh(world, inspection);
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
        var refresh = refresh(world, inspection);
        refresh.start();
        refresh.tick();
        refresh.tick();
        inspection.session.pollFailure = new IllegalStateException("unsafe hand");
        assertThrows(IllegalStateException.class, refresh::tick);
        assertEquals(1, inspection.session.canceled);
    }

    @Test
    void disabledCategorySwitchDoesNotScanOrOpenAnyContainer() {
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
        assertEquals(List.of(PARTNER, LAST, SECOND_STORAGE), inspection.session.opened);
        assertEquals(STANDING, inspection.session.targets.getFirst().standingArea());
        assertEquals(List.of(16, -10), world.scanned.stream()
                .map(RegionDefinition::firstX).toList());
        assertEquals(0, cache.writes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void internalMarkerRefreshKeepsItsOriginalSessionAndOrder(boolean enabled) {
        var world = new World();
        world.loadAll = true;
        world.scanTargets = List.of(new ContainerTarget(MARKER, "minecraft:chest"));
        var inspection = new Inspection();
        var cache = new MarkerCache();
        var refresh = categoryRefresh(world, inspection, cache,
                Map.of("categoryStorageOnlyEnabled", Boolean.toString(enabled),
                        "markerOnly", "true", "reuseSafeHandEnabled", "true",
                        "nearbyRouteEnabled", "true"));
        assertEquals(TaskModuleState.RUNNING, refresh.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, finish(refresh).state());
        assertEquals(List.of(MARKER), inspection.opened);
        assertEquals(1, cache.writes);
        assertEquals(0, inspection.sessions);
    }

    @Test
    void categorySwitchDoesNotChangeOrdinaryRegionRefresh() {
        var world = new World();
        var inspection = new Inspection();
        var refresh = refresh(world, inspection, Map.of("categoryStorageOnlyEnabled", "true"));
        refresh.start();
        assertEquals(TaskModuleState.FAILED, finish(refresh).state());
        assertEquals(List.of(FIRST, BLOCKED, LAST), inspection.session.opened);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void classificationMarkerRefreshUsesBaselineStancesAndOrdinaryRefreshRemainsIsolated(
            boolean rangeEnabled) {
        for (boolean enabled : List.of(false, true)) {
            var world = new World();
            world.loadAll = true;
            world.scanTargets = List.of(new ContainerTarget(MARKER, "minecraft:chest"));
            var inspection = new Inspection();
            var markers = new AreaBounds(64, 64, 0, 64, 64, 0);
            var plan = new dev.stowling.v2.model.CategoryStandingPlan(true, List.of(
                    new dev.stowling.v2.model.CategoryStandingPoint(
                            new dev.stowling.v2.model.CategoryPositionDraft(20, 64, 0),
                            null, null, false)));
            var binding = new CategoryBindingDefinition(UUID.randomUUID(), markers, markers,
                    ContainerContentsCategoryRule.ID, List.of(), List.of(), null, null,
                    false, "组", false, null, plan);
            var zone = new CategoryZoneDefinition(UUID.randomUUID(), "仓库", List.of(binding));
            var ports = TaskModuleRuntimeRegistry.unavailablePorts(world);
            var workspace = new TaskRunWorkspace();
            workspace.configureClassificationTask(enabled);
            workspace.configureStandingRange(rangeEnabled);
            var context = new TaskExecutionContext(world, ports.droppedItems(),
                    ports.containerRegions(), ports.containerTransfers(), ports.containerWithdrawals(),
                    inspection, new MarkerCache(), ports.categoryZones(), Map.of(), Map.of(),
                    Map.of(zone.id(), zone), workspace);
            var options = ContainerCacheRefresh.from(Map.of("targetRef", "category-zone:" + zone.id(),
                    "markerOnly", "true"));
            var refresh = new MinecraftContainerCacheRefresh(context, options, world);
            refresh.start();
            assertEquals(TaskModuleState.SUCCEEDED, finish(refresh).state());
            assertEquals(enabled ? STANDING : null, inspection.targets.getFirst().standingArea());
            assertEquals(enabled && rangeEnabled,
                    inspection.targets.getFirst().standingRangeEnabled());
        }
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
        assertEquals(List.of(FIRST, BLOCKED, LAST), inspection.session.opened);
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
        assertEquals(1, inspection.session.canceled);
        assertEquals(List.of(FIRST), inspection.session.opened);
    }

    @Test
    void splitsAtNegativeAndPositiveChunkEdgesWithoutExtendingTheSelection() {
        var area = new AreaBounds(16, 70, 0, -1, 64, 0);
        var chunks = ContainerCacheRefreshPlan.chunks(UUID.randomUUID(), "Test", area);
        assertEquals(List.of(-1, 0, 16), chunks.stream().map(RegionDefinition::firstX).toList());
        assertEquals(List.of(-1, 15, 16), chunks.stream().map(RegionDefinition::secondX).toList());
    }

    private static MinecraftContainerCacheRefresh refresh(World world, Inspection inspection) {
        return refresh(world, inspection, Map.of());
    }

    private static MinecraftContainerCacheRefresh refresh(World world, Inspection inspection,
            Map<String, String> parameters) {
        UUID regionId = UUID.randomUUID();
        var region = new RegionDefinition(regionId, "Test", 15, 64, 0, 18, 64, 0);
        var ports = TaskModuleRuntimeRegistry.unavailablePorts(world);
        var context = new TaskExecutionContext(world, ports.droppedItems(),
                ports.containerRegions(), ports.containerTransfers(), ports.containerWithdrawals(),
                inspection, CategoryMarkerCachePort.UNAVAILABLE, ports.categoryZones(), Map.of(),
                Map.of(regionId, region), Map.of(), new TaskRunWorkspace());
        var values = new java.util.HashMap<>(parameters);
        values.put("targetRef", "region:" + regionId);
        return new MinecraftContainerCacheRefresh(context, ContainerCacheRefresh.from(values), world);
    }

    private static final class World
            implements MinecraftContainerCacheRefresh.RefreshWorld, NavigationPort {
        private final List<BlockPosition> travel = new ArrayList<>();
        private final List<RegionDefinition> scanned = new ArrayList<>();
        private List<ContainerTarget> scanTargets;
        private Map<UUID, List<ContainerTarget>> regionTargets;
        private boolean loadAll;
        private boolean available = true;
        private Set<BlockPosition> reachable = Set.of();
        private Object memory;
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
        public boolean inReach(BlockPosition target) {
            return reachable.contains(target);
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
            if (regionTargets != null) return ContainerScanResult.success(
                    regionTargets.get(chunk.id()));
            if (scanTargets != null) return ContainerScanResult.success(scanTargets);
            var chest = new ContainerTarget(FIRST, "minecraft:chest", null, null, PARTNER);
            return ContainerScanResult.success(chunk.firstX() < 16 ? List.of(chest)
                    : List.of(chest, new ContainerTarget(BLOCKED, "minecraft:chest"),
                            new ContainerTarget(LAST, "minecraft:barrel")));
        }

        @Override
        public Object memoryStamp(ContainerTarget target) {
            return memory;
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
        private boolean canPauseBeforeOpen;
        @Override
        public ContainerInspectionPort openReadSession() {
            sessions++;
            session = new Inspection();
            session.canPauseBeforeOpen = canPauseBeforeOpen;
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
        public boolean canPauseBeforeOpen() {
            return canPauseBeforeOpen;
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
