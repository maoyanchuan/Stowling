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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DroppedItemCollectionEngineTest {
    private static final String GOLD = "minecraft:gold_ingot";
    private static final String IRON = "minecraft:iron_ingot";

    @Test
    void aNewCollectionFlushesFullInventoryOwnedByThePreviousCollectionsLateReceipt() {
        MutableInventory inventory = new MutableInventory();
        MutableClock clock = new MutableClock();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory, clock);
        manager.observe();
        PlayerInventoryAccount previous = manager.account(UUID.randomUUID());
        previous.beginCollection(Set.of());
        inventory.set(Map.of(GOLD, 64));
        manager.observe();
        manager.claimCollectionItems(previous, Set.of());
        previous.confirmDeposit("collected", Map.of(GOLD, 64), Map.of());
        inventory.set(Map.of());
        manager.observe();
        manager.closeAccount(previous);
        inventory.set(Map.of(GOLD, 2240));
        manager.observe();
        manager.reconcileUnassignedItems();
        MutableDrops drops = new MutableDrops();
        entries(drops, item(1, GOLD, 64));
        drops.blockedItems = Set.of(GOLD);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        var engine = new DroppedItemCollectionEngine(context(workspace), drops, 120,
                Set.of(), manager, clock);

        assertEquals(TaskModuleState.SUCCEEDED, engine.start().state());
        assertEquals(Map.of(GOLD, 2240), workspace.collectedItems());
        assertTrue(previous.managedItems().isEmpty());
    }

    @Test
    void allUnprotectedKindsAreCollectedWhilePersonalToolsStayProtected() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        entries(drops, item(1, GOLD, 3));
        inventory.set(Map.of("minecraft:diamond_pickaxe", 1));
        DroppedItemCollectionEngine engine = engine(drops, inventory, workspace, new MutableClock());
        engine.start();
        engine.tick();
        drops.clear();
        String disc = "minecraft:music_disc_otherside";
        inventory.set(Map.of(GOLD, 3, disc, 2, "minecraft:diamond_pickaxe", 1));

        assertEquals(TaskModuleState.SUCCEEDED, tickUntilFinished(engine, 45).state());
        assertEquals(Map.of(GOLD, 3, disc, 2), workspace.collectedItems());
        assertEquals(Map.of(GOLD, 3, disc, 2), workspace.managedItems());
    }

    @Test
    void capturesTheTaskStartInventoryBeforeNavigating() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        entries(drops, item(10, GOLD, 1));
        inventory.set(Map.of(IRON, 4));
        DroppedItemCollectionEngine engine = engine(
                drops,
                inventory,
                workspace,
                new MutableClock()
        );

        assertEquals(TaskModuleState.RUNNING, engine.start().state());

        assertEquals(
                Map.of(IRON, 4),
                workspace.initialInventory().orElseThrow().itemCounts()
        );
    }

    @Test
    void reroutesWhenTheSameDropMovesAndCancelsWhenItDisappears() {
        MutableDrops drops = new MutableDrops();
        RecordingNavigation navigation = new RecordingNavigation();
        DroppedItem original = item(10, GOLD, 1);
        BlockPosition landed = original.position().offset(1, -1, 0);
        entries(drops, original);
        var engine = engine(drops, new MutableInventory(), new TaskRunWorkspace(),
                new MutableClock(), 120, navigation);

        assertEquals(TaskModuleState.RUNNING, engine.start().state());
        entries(drops, new DroppedItem(original.entityId(), GOLD, 1, landed));
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertEquals(List.of(original.position(), landed), navigation.targets);
        assertEquals(1, navigation.cancellations);

        drops.clear();
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertEquals(2, navigation.cancellations);
    }

    @Test
    void retriesATemporaryPathFailureWithinTheSameCollection() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        MutableClock clock = new MutableClock();
        RecordingNavigation navigation = new RecordingNavigation();
        DroppedItem drop = item(10, GOLD, 1);
        entries(drops, drop);
        var engine = engine(drops, inventory, workspace, clock, 120, navigation);

        assertEquals(TaskModuleState.RUNNING, engine.start().state());
        navigation.status = NavigationStatus.FAILED;
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        clock.advance(Duration.ofMillis(499));
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertEquals(List.of(drop.position()), navigation.targets);

        clock.advance(Duration.ofMillis(1));
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        assertEquals(List.of(drop.position(), drop.position()), navigation.targets);

        navigation.status = NavigationStatus.ARRIVED;
        assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        drops.clear();
        inventory.set(Map.of(GOLD, 1));
        assertEquals(TaskModuleState.SUCCEEDED, tickUntilFinished(engine, 45).state());
        assertEquals(Map.of(GOLD, 1), workspace.collectedItems());
    }

    @Test
    void anUnreachableStationaryDropFailsAfterTwoRetries() {
        MutableDrops drops = new MutableDrops();
        MutableClock clock = new MutableClock();
        RecordingNavigation navigation = new RecordingNavigation();
        entries(drops, item(10, GOLD, 1));
        var engine = engine(drops, new MutableInventory(), new TaskRunWorkspace(),
                clock, 120, navigation);

        assertEquals(TaskModuleState.RUNNING, engine.start().state());
        navigation.status = NavigationStatus.FAILED;
        for (int retry = 0; retry < 2; retry++) {
            assertEquals(TaskModuleState.RUNNING, engine.tick().state());
            clock.advance(Duration.ofMillis(500));
            assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        }

        assertEquals(TaskModuleState.FAILED, engine.tick().state());
        assertEquals(3, navigation.targets.size());
    }

    @Test
    void booksConfirmedPickupsImmediatelyButWaitsForStableCompletion() {
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
    void fullInventoryYieldsTheConfirmedBatchWhileTheSameDiscKeepsArriving() {
        String disc = "minecraft:music_disc_otherside";
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        entries(drops, item(1, disc, 1));
        inventory.set(Map.of(IRON, 4));
        DroppedItemCollectionEngine engine = engine(drops, inventory, workspace, new MutableClock());
        engine.start();

        // More discs arrived and were picked up between two entity scans.
        entries(drops, item(2, disc, 1));
        inventory.set(Map.of(IRON, 4, disc, 32));
        drops.blockedItems = Set.of(disc);
        TaskModuleResult result = engine.tick();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertTrue(result.message().contains("先将本批物品存入缓存"));
        assertEquals(Map.of(disc, 32), workspace.collectedItems());
        assertEquals(Map.of(disc, 32), workspace.managedItems());
    }

    @Test
    void fullPersonalInventoryFailsClearlyWithoutRepeatedEmptyBatches() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        entries(drops, item(1, GOLD, 1));
        inventory.set(Map.of(IRON, 36));
        drops.blockedItems = Set.of(GOLD);

        TaskModuleResult result = engine(drops, inventory, workspace, new MutableClock()).start();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("背包空间不足"));
        assertTrue(workspace.managedItems().isEmpty());
    }

    @Test
    void usesRemainingStackSpaceForAnotherDropBeforeDepositing() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        String disc = "minecraft:music_disc_otherside";
        entries(drops, item(1, disc, 1), item(2, GOLD, 1));
        drops.blockedItems = Set.of(disc);
        inventory.set(Map.of(GOLD, 63));
        DroppedItemCollectionEngine engine = engine(drops, inventory, workspace, new MutableClock());

        assertEquals(TaskModuleState.RUNNING, engine.start().state());
        inventory.set(Map.of(GOLD, 64));
        drops.blockedItems = Set.of(disc, GOLD);

        assertEquals(TaskModuleState.SUCCEEDED, engine.tick().state());
        assertEquals(Map.of(GOLD, 1), workspace.collectedItems());
    }

    @Test
    void stoppingAtAnUnpickableEntityDoesNotWaitForever() {
        MutableDrops drops = new MutableDrops();
        MutableInventory inventory = new MutableInventory();
        entries(drops, item(1, GOLD, 1));
        DroppedItemCollectionEngine engine = engine(drops, inventory,
                new TaskRunWorkspace(), new MutableClock());
        engine.start();
        drops.blockedItems = Set.of(GOLD);
        engine.requestStop();

        assertTrue(engine.safeToStop());
        assertEquals(TaskModuleState.SUCCEEDED, engine.tick().state());
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
        for (int tick = 0; tick < 18; tick++) {
            assertEquals(TaskModuleState.RUNNING, engine.tick().state());
        }

        entries(drops, item(2, GOLD, 1));
        TaskModuleResult interrupted = engine.tick();

        assertEquals(TaskModuleState.RUNNING, interrupted.state());
        assertNotEquals(
                "掉落物已拾取完毕",
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
    void unavailableInventoryFailsBecauseNoTaskSnapshotExists() {
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

        TaskModuleResult result = engine.start();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("背包快照"));
        assertTrue(workspace.initialInventory().isEmpty());
        assertTrue(workspace.collectedItems().isEmpty());
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
        return engine(drops, inventory, workspace, clock, timeoutSeconds,
                new AlwaysArrivedNavigation());
    }

    private static DroppedItemCollectionEngine engine(
            MutableDrops drops,
            MutableInventory inventory,
            TaskRunWorkspace workspace,
            MutableClock clock,
            int timeoutSeconds,
            NavigationPort navigation
    ) {
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        TaskExecutionContext context = context(workspace, navigation);
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
        return context(workspace, new AlwaysArrivedNavigation());
    }

    private static TaskExecutionContext context(TaskRunWorkspace workspace,
            NavigationPort navigation) {
        return new TaskExecutionContext(
                navigation,
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
        private Set<String> blockedItems = Set.of();

        @Override
        public boolean canPickUp(DroppedItem item) {
            return !blockedItems.contains(item.itemId());
        }

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

    private static final class RecordingNavigation implements NavigationPort {
        private final List<BlockPosition> targets = new ArrayList<>();
        private NavigationStatus status = NavigationStatus.NAVIGATING;
        private int cancellations;

        @Override
        public NavigationStatus start(BlockPosition target) {
            targets.add(target);
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            assertEquals(targets.getLast(), target);
            return status;
        }

        @Override
        public void cancel() {
            cancellations++;
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
