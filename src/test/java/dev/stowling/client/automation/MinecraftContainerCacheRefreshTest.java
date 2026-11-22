package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftContainerCacheRefreshTest {
    private static final BlockPosition FIRST = new BlockPosition(15, 64, 0);
    private static final BlockPosition PARTNER = new BlockPosition(16, 64, 0);
    private static final BlockPosition BLOCKED = new BlockPosition(17, 64, 0);
    private static final BlockPosition LAST = new BlockPosition(18, 64, 0);

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
        refresh.cancel();

        assertEquals(TaskModuleState.SUCCEEDED, refresh.tick().state());
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
        UUID regionId = UUID.randomUUID();
        var region = new RegionDefinition(regionId, "Test", 15, 64, 0, 18, 64, 0);
        var ports = TaskModuleRuntimeRegistry.unavailablePorts(world);
        var context = new TaskExecutionContext(world, ports.droppedItems(),
                ports.containerRegions(), ports.containerTransfers(), ports.containerWithdrawals(),
                inspection, CategoryMarkerCachePort.UNAVAILABLE, ports.categoryZones(), Map.of(),
                Map.of(regionId, region), Map.of(), new TaskRunWorkspace());
        var settings = ContainerCacheRefresh.from(Map.of("targetRef", "region:" + regionId));
        return new MinecraftContainerCacheRefresh(context, settings, world);
    }

    private static final class World
            implements MinecraftContainerCacheRefresh.RefreshWorld, NavigationPort {
        private final List<BlockPosition> travel = new ArrayList<>();
        private int stopCount;

        @Override
        public boolean available() {
            return true;
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
            return center.x() < 16 || !travel.isEmpty();
        }

        @Override
        public ContainerScanResult scan(RegionDefinition chunk, ContainerCacheRefresh options) {
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
        private int canceled;

        @Override
        public ContainerInspectionResult startInspection(ContainerTarget target) {
            opened.add(target.position());
            return ContainerInspectionResult.pending("pending");
        }

        @Override
        public ContainerInspectionResult pollInspection() {
            return BLOCKED.equals(opened.getLast())
                    ? ContainerInspectionResult.failed("cannot open")
                    : ContainerInspectionResult.success("empty", Set.of());
        }

        @Override
        public void cancelInspection() {
            canceled++;
        }
    }
}
