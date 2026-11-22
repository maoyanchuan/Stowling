package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskFlowTemplates;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.TaskFlowExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DroppedItemCollectionFlowTest {
    private static final String DISC = "minecraft:music_disc_otherside";
    private static final String TOOL = "minecraft:diamond_pickaxe";
    private static final BlockPosition PICKUP = new BlockPosition(0, 64, 0);

    @Test
    void depositsFullBatchesThenResumesAndLeavesOnlyPersonalItemsAtCompletion() {
        Scenario scenario = new Scenario(true);
        TaskRunWorkspace workspace = new TaskRunWorkspace();

        TaskModuleResult result = run(scenario, workspace);

        assertEquals(TaskModuleState.SUCCEEDED, result.state(), result.message());
        assertEquals(List.of(4, 4, 3), scenario.deposits);
        assertEquals(0, scenario.separateContainerMoves);
        assertEquals(0, scenario.remaining);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.playerItemCounts().orElseThrow());
        assertTrue(workspace.managedItems().isEmpty());
        assertTrue(workspace.collectedItems().isEmpty());
    }

    @Test
    void aFullCacheDoesNotStartAnotherPickupBatchWithoutFreeingSpace() {
        Scenario scenario = new Scenario(false);
        TaskRunWorkspace workspace = new TaskRunWorkspace();

        TaskModuleResult result = run(scenario, workspace);

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals(7, scenario.remaining);
        assertTrue(scenario.deposits.isEmpty());
        assertEquals(Map.of(DISC, 4), workspace.managedItems());
    }

    private static TaskModuleResult run(Scenario scenario, TaskRunWorkspace workspace) {
        var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
        var region = new RegionDefinition(UUID.randomUUID(), "Cache", 10, 64, 0, 10, 64, 0);
        var cache = new ContainerTarget(new BlockPosition(10, 64, 0), "minecraft:chest");
        var spare = new ContainerTarget(new BlockPosition(11, 64, 0), "minecraft:chest");
        var registry = TaskModuleRuntimeRegistry.unavailablePorts(scenario);
        var context = new TaskExecutionContext(scenario, scenario,
                (area, types, volume) -> ContainerScanResult.success(List.of(cache, spare)),
                scenario,
                registry.containerWithdrawals(), registry.categoryZones(),
                Map.of(point.id(), point), Map.of(region.id(), region), Map.of(), workspace);
        var flow = new TaskFlowExecutor(TaskFlowTemplates.collect("coordinate:" + point.id(),
                "region:" + region.id(), Map.of()), context, new TaskFlowExecutor.Host() {
                    @Override
                    public TaskModuleExecutor createAction(TaskStep step) {
                        return registry.create(step, context);
                    }

                    @Override
                    public void createTask(String taskRef, TaskPriority priority) {
                        throw new AssertionError("unexpected classification handoff");
                    }
                }, false);
        TaskModuleResult result = TaskModuleResult.running("starting");
        for (int tick = 0; tick < 400; tick++) {
            scenario.advance();
            result = flow.tick();
            if (result.state() == TaskModuleState.SUCCEEDED
                    || result.state() == TaskModuleState.FAILED) return result;
        }
        return result;
    }

    private static final class Scenario
            implements NavigationPort, DroppedItemObserverPort, ContainerTransferPort {
        private final boolean acceptsDeposits;
        private final List<Integer> deposits = new ArrayList<>();
        private int remaining = 11;
        private int carried = 2;
        private int separateContainerMoves;
        private boolean atPickup;
        private boolean latePickup;

        private Scenario(boolean acceptsDeposits) {
            this.acceptsDeposits = acceptsDeposits;
        }

        private void advance() {
            if (atPickup && remaining > 0 && carried < 6) {
                remaining--;
                carried++;
            }
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            atPickup = target.equals(PICKUP);
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus startInteraction(BlockPosition target,
                dev.stowling.v2.model.AreaBounds standingArea) {
            separateContainerMoves++;
            return start(target);
        }

        @Override
        public void cancel() {
            atPickup = false;
        }

        @Override
        public List<DroppedItem> observe(BlockPosition center, int radius) {
            return remaining == 0 ? List.of()
                    : List.of(new DroppedItem(new UUID(0, remaining), DISC, 1, PICKUP));
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return observe(PICKUP, 1);
        }

        @Override
        public boolean canPickUp(DroppedItem item) {
            return carried < 6;
        }

        @Override
        public Optional<Map<String, Integer>> playerItemCounts() {
            return Optional.of(Map.of(DISC, carried, TOOL, 1));
        }

        @Override
        public ContainerTransferResult startDeposit(ContainerTarget target,
                Map<String, Integer> items) {
            throw new AssertionError("collected cache deposits use their own approach policy");
        }

        @Override
        public ContainerTransferResult startCollectedCacheDeposit(ContainerTarget target,
                Map<String, Integer> items) {
            atPickup = false;
            if (!acceptsDeposits) return ContainerTransferResult.failed("cache full", items);
            int count = items.getOrDefault(DISC, 0);
            carried -= count;
            deposits.add(count);
            // Confirm a delayed pickup during the transfer, before the next collection starts.
            if (!latePickup && remaining > 0) {
                latePickup = true;
                carried++;
                remaining--;
            }
            return ContainerTransferResult.succeeded("cached", Map.of());
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            throw new AssertionError("synchronous transfer");
        }

        @Override
        public void cancelDeposit() {
        }
    }
}
