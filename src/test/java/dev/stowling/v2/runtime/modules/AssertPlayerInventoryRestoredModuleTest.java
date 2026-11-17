package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssertPlayerInventoryRestoredModuleTest {
    private static final String GOLD = "minecraft:gold_ingot";

    @Test
    void succeedsWhenTheFinalSnapshotMatchesTheTaskStart() {
        MutableDroppedItems dropped = new MutableDroppedItems(
                Map.of("minecraft:iron_ingot", 4)
        );
        TaskRunWorkspace workspace = workspace(dropped.counts());
        AssertPlayerInventoryRestoredModule module = module(
                dropped,
                workspace
        );

        TaskModuleResult result = module.start();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertTrue(result.message().contains("恢复到任务前快照"));
    }

    @Test
    void waitsForDelayedClientInventorySynchronization() {
        MutableDroppedItems dropped = new MutableDroppedItems(
                Map.of("minecraft:iron_ingot", 5)
        );
        TaskRunWorkspace workspace = workspace(dropped.counts());
        AssertPlayerInventoryRestoredModule module = module(
                dropped,
                workspace
        );
        dropped.set(Map.of("minecraft:iron_ingot", 7));

        assertEquals(TaskModuleState.WAITING, module.start().state());
        dropped.set(Map.of("minecraft:iron_ingot", 5));

        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
    }

    @Test
    void failsAfterTheSnapshotSettleWindow() {
        MutableDroppedItems dropped = new MutableDroppedItems(
                Map.of("minecraft:iron_ingot", 5)
        );
        TaskRunWorkspace workspace = workspace(dropped.counts());
        AssertPlayerInventoryRestoredModule module = module(
                dropped,
                workspace
        );
        dropped.set(Map.of("minecraft:iron_ingot", 7));

        TaskModuleResult result = module.start();
        for (int tick = 1; tick < 20; tick++) {
            assertEquals(TaskModuleState.WAITING, result.state());
            result = module.tick();
        }

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("新增={minecraft:iron_ingot=2}"));
    }

    @Test
    void failsWhenTheTaskHasNoStartSnapshot() {
        MutableDroppedItems dropped = new MutableDroppedItems(Map.of());
        AssertPlayerInventoryRestoredModule module = module(
                dropped,
                new TaskRunWorkspace()
        );

        TaskModuleResult result = module.start();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("开始前"));
    }

    @Test
    void syncRecordsLateInventoryAdditionsForAnotherDeposit() {
        MutableDroppedItems dropped = new MutableDroppedItems(Map.of());
        TaskRunWorkspace workspace = workspace(Map.of());
        SyncCollectedInventoryModule module = syncModule(dropped, workspace);
        dropped.set(Map.of(GOLD, 3));

        TaskModuleResult result = module.start();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(Map.of(GOLD, 3), workspace.collectedItems());
        assertEquals(Map.of(GOLD, 3), workspace.managedItems());
    }

    @Test
    void syncDoesNotRecordAnItemAlreadyDeposited() {
        MutableDroppedItems dropped = new MutableDroppedItems(Map.of());
        TaskRunWorkspace workspace = workspace(Map.of());
        SyncCollectedInventoryModule initialSync = syncModule(
                dropped,
                workspace
        );
        dropped.set(Map.of(GOLD, 3));
        initialSync.start();
        workspace.consumeDeposit(
                "collected",
                Map.of(GOLD, 3),
                Map.of()
        );
        dropped.set(Map.of());

        SyncCollectedInventoryModule afterDeposit = syncModule(
                dropped,
                workspace
        );
        TaskModuleResult result = afterDeposit.start();
        for (int tick = 1; tick < 40; tick++) {
            assertEquals(TaskModuleState.WAITING, result.state());
            result = afterDeposit.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertTrue(workspace.collectedItems().isEmpty());
        dropped.set(Map.of(GOLD, 1));
        SyncCollectedInventoryModule lateSync = syncModule(
                dropped,
                workspace
        );
        assertEquals(
                TaskModuleState.SUCCEEDED,
                lateSync.start().state()
        );
        assertEquals(Map.of(GOLD, 1), workspace.collectedItems());
    }

    @Test
    void syncWaitsForFortyStableBaselineTicks() {
        MutableDroppedItems dropped = new MutableDroppedItems(
                Map.of("minecraft:iron_ingot", 5)
        );
        TaskRunWorkspace workspace = workspace(dropped.counts());
        SyncCollectedInventoryModule module = syncModule(dropped, workspace);

        TaskModuleResult result = module.start();
        for (int tick = 1; tick < 40; tick++) {
            assertEquals(TaskModuleState.WAITING, result.state());
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
    }

    private static TaskRunWorkspace workspace(
            Map<String, Integer> counts
    ) {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.captureInitialInventory(counts);
        return workspace;
    }

    private static AssertPlayerInventoryRestoredModule module(
            MutableDroppedItems dropped,
            TaskRunWorkspace workspace
    ) {
        TaskExecutionContext context = context(dropped, workspace);
        return new AssertPlayerInventoryRestoredModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.ASSERT_PLAYER_INVENTORY_RESTORED,
                        Map.of()
                )
        );
    }

    private static SyncCollectedInventoryModule syncModule(
            MutableDroppedItems dropped,
            TaskRunWorkspace workspace
    ) {
        return new SyncCollectedInventoryModule(
                context(dropped, workspace),
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.SYNC_COLLECTED_INVENTORY,
                        Map.of()
                )
        );
    }

    private static TaskExecutionContext context(
            DroppedItemObserverPort dropped,
            TaskRunWorkspace workspace
    ) {
        ContainerRegionPort regions = (region, types, volume) ->
                ContainerScanResult.failure("unused");
        return new TaskExecutionContext(
                new IdleNavigation(),
                dropped,
                regions,
                new UnusedTransfers(),
                new UnusedWithdrawals(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                FoodConsumptionPort.UNAVAILABLE,
                (zone, types, volume) ->
                        CategoryZoneResult.failure("unused"),
                Map.<UUID, CoordinatePoint>of(),
                Map.<UUID, RegionDefinition>of(),
                Map.<UUID, CategoryZoneDefinition>of(),
                workspace
        );
    }

    private static final class MutableDroppedItems
            implements DroppedItemObserverPort {
        private Map<String, Integer> counts;

        private MutableDroppedItems(Map<String, Integer> counts) {
            this.counts = Map.copyOf(counts);
        }

        @Override
        public List<DroppedItem> observe(
                BlockPosition center,
                int radius
        ) {
            return List.of();
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return List.of();
        }

        @Override
        public Optional<Map<String, Integer>> playerItemCounts() {
            return Optional.of(counts);
        }

        private Map<String, Integer> counts() {
            return counts;
        }

        private void set(Map<String, Integer> counts) {
            this.counts = Map.copyOf(counts);
        }
    }

    private static final class IdleNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class UnusedTransfers
            implements ContainerTransferPort {
        @Override
        public ContainerTransferResult startDeposit(
                ContainerTarget target,
                Map<String, Integer> items
        ) {
            return ContainerTransferResult.failed("unused", Map.of());
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            return ContainerTransferResult.failed("unused", Map.of());
        }

        @Override
        public void cancelDeposit() {
        }
    }

    private static final class UnusedWithdrawals
            implements ContainerWithdrawalPort {
        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            return ContainerWithdrawalResult.failed(
                    "unused",
                    Map.of(),
                    false
            );
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.failed(
                    "unused",
                    Map.of(),
                    false
            );
        }

        @Override
        public void cancelWithdrawal() {
        }
    }
}
