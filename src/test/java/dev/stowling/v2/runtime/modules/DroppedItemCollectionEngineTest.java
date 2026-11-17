package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DroppedItemCollectionEngineTest {
    private static final String GOLD = "minecraft:gold_ingot";

    @Test
    void recordsDelayedInventoryAdditionOnceAndWaitsForStableState() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        MutableClock clock = new MutableClock();
        entries(drops, item(1, GOLD, 3));
        inventory.set(Map.of());
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                clock
        );

        assertEquals(TaskModuleState.RUNNING, engine.start().state());
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        drops.clear();

        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertTrue(workspace.collectedItems().isEmpty());

        inventory.set(Map.of(GOLD, 3));
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertEquals(Map.of(GOLD, 3), workspace.collectedItems());

        TaskModuleResult settled = tickUntilFinished(engine, 45);

        assertEquals(TaskModuleState.SUCCEEDED, settled.state());
        assertEquals(Map.of(GOLD, 3), workspace.collectedItems());
    }

    @Test
    void newDropResetsStableConfirmationBeforeCompletion() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        MutableClock clock = new MutableClock();
        inventory.set(Map.of());
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                clock
        );

        assertEquals(TaskModuleState.RUNNING, engine.start().state());
        for (int tick = 0; tick < 38; tick++) {
            assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        }

        entries(drops, item(2, GOLD, 1));
        TaskModuleResult interrupted = engine.tick();

        assertEquals(TaskModuleState.RUNNING, interrupted.state());
        assertNotEquals(
                "掉落物已拾取完毕，背包已稳定",
                interrupted.message()
        );
    }

    @Test
    void entityDisappearanceWithoutInventorySyncDoesNotCompleteImmediately() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        entries(drops, item(3, GOLD, 2));
        inventory.set(Map.of());
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                new MutableClock()
        );

        engine.start();
        engine.tick();
        drops.clear();
        TaskModuleResult result = engine.tick();

        assertEquals(TaskModuleState.RUNNING, result.state());
        assertTrue(workspace.collectedItems().isEmpty());
    }

    @Test
    void continuousIncomingDropsPreventEarlyCompletion() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        MutableClock clock = new MutableClock();
        inventory.set(Map.of());
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                clock
        );

        engine.start();
        for (int tick = 0; tick < 20; tick++) {
            entries(drops, item(100 + tick, GOLD, 1));
            clock.advance(Duration.ofMillis(400));
            TaskModuleResult result = engine.tick();
            assertEquals(TaskModuleState.RUNNING, result.state());
        }
    }

    @Test
    void stationaryDropStillTimesOut() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        MutableClock clock = new MutableClock();
        entries(drops, item(4, GOLD, 1));
        inventory.set(Map.of());
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                clock,
                2
        );

        engine.start();
        clock.advance(Duration.ofSeconds(3));
        TaskModuleResult result = engine.tick();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("超时"));
    }

    @Test
    void unavailableInventoryFallsBackToEntityCountOnce() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        entries(drops, item(5, GOLD, 2));
        inventory.unavailable();
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                new MutableClock()
        );

        engine.start();
        engine.tick();
        drops.clear();
        engine.tick();

        assertEquals(Map.of(GOLD, 2), workspace.collectedItems());
        tickUntilFinished(engine, 45);
        assertEquals(Map.of(GOLD, 2), workspace.collectedItems());
    }

    private static DroppedItemCollectionEngine engine(
            MutableDrops drops,
            MutableInventory inventory,
            TaskRunWorkspace workspace,
            MutableClock clock
    ) {
        return engine(drops, inventory, workspace, clock, 120);
    }

    private static DroppedItemCollectionEngine engine(
            MutableDrops drops,
            MutableInventory inventory,
            TaskRunWorkspace workspace,
            MutableClock clock,
            int timeoutSeconds
    ) {
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        TaskExecutionContext context = context(workspace);
        return new DroppedItemCollectionEngine(
                context,
                drops,
                timeoutSeconds,
                Set.of(),
                manager,
                clock
        );
    }

    private static TaskModuleResult tickUntilFinished(
            DroppedItemCollectionEngine engine,
            int maximumTicks
    ) {
        TaskModuleResult result = engine.tick();
        for (int tick = 1; tick < maximumTicks; tick++) {
            if (result.state() == TaskModuleState.SUCCEEDED) {
                return result;
            }
            result = engine.tick();
        }
        return result;
    }

    private static TaskExecutionContext context(TaskRunWorkspace workspace) {
        return new TaskExecutionContext(
                new AlwaysArrivedNavigation(),
                new EmptyDroppedItems(),
                (region, types, volume) ->
                        ContainerScanResult.failure("unused"),
                new UnusedTransfers(),
                new UnusedWithdrawals(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                FoodConsumptionPort.UNAVAILABLE,
                (zone, types, volume) ->
                        dev.stowling.automation.CategoryZoneResult.failure(
                                "unused"
                        ),
                Map.of(),
                Map.of(),
                Map.of(),
                workspace
        );
    }

    private static DroppedItem item(
            int id,
            String itemId,
            int count
    ) {
        return new DroppedItem(
                new UUID(0, id),
                itemId,
                count,
                new BlockPosition(id, 64, 0)
        );
    }

    private static void entries(
            MutableDrops drops,
            DroppedItem... items
    ) {
        drops.set(List.of(items));
    }

    private static final class MutableDrops
            implements DroppedItemCollectionEngine.Source {
        private List<DroppedItem> items = List.of();

        @Override
        public boolean dimensionMatches() {
            return true;
        }

        @Override
        public List<DroppedItem> observe() {
            return items;
        }

        private void set(List<DroppedItem> items) {
            this.items = List.copyOf(items);
        }

        private void clear() {
            items = List.of();
        }
    }

    private static final class MutableInventory
            implements PlayerInventoryManager.Source {
        private Optional<PlayerInventorySnapshot> snapshot =
                Optional.of(new PlayerInventorySnapshot(Map.of()));

        @Override
        public Optional<PlayerInventorySnapshot> snapshot() {
            return snapshot;
        }

        private void set(Map<String, Integer> counts) {
            snapshot = Optional.of(new PlayerInventorySnapshot(counts));
        }

        private void unavailable() {
            snapshot = Optional.empty();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed test clock");
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class AlwaysArrivedNavigation
            implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class EmptyDroppedItems
            implements DroppedItemObserverPort {
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
    }

    private static final class UnusedTransfers
            implements ContainerTransferPort {
        @Override
        public ContainerTransferResult startDeposit(
                dev.stowling.automation.ContainerTarget target,
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
                dev.stowling.automation.ContainerTarget target,
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
