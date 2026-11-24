package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PickupReceipt;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.InventorySlotFixture;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.TaskRunState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionPointIntakeTest {
    private static final String WORLD = "minecraft:overworld";
    private static final String TOOL = "minecraft:netherite_pickaxe";
    private static final BlockPosition POINT = new BlockPosition(0, 64, 0);
    private static final Map<String, Integer> PERSONAL = Map.of(TOOL, 1, box(0), 1);

    @Test
    void fullFirstBatchBeforeTriggerAndAfterTriggerBothMakeTwoTrips() {
        for (boolean beforeTrigger : List.of(true, false)) {
            Warehouse warehouse = new Warehouse(52);
            if (!beforeTrigger) {
                warehouse.untilStarted();
            }
            warehouse.pickupBatch();
            warehouse.finish();

            assertEquals(List.of(34, 18), warehouse.deposits);
            assertEquals(PERSONAL, warehouse.backpack);
            assertEquals(52, warehouse.cached.size());
            for (int index = 0; index < 52; index++) {
                assertEquals(1, warehouse.cached.get(box(index)));
            }
        }
    }

    @Test
    void receiptsCanStartACollectionAfterAllEntitiesWerePickedUp() {
        Warehouse warehouse = new Warehouse(2);
        warehouse.pickupBatch();
        assertTrue(warehouse.observe(POINT, 2).isEmpty());
        warehouse.finish();
        assertEquals(List.of(2), warehouse.deposits);
        assertEquals(PERSONAL, warehouse.backpack);
    }

    @Test
    void receiptsAfterAnIdleFullSnapshotCanStillUnloadTheFirstBatch() {
        Warehouse warehouse = new Warehouse(52);
        for (int i = 0; i < 34; i++) warehouse.backpack.merge(box(i), 1, Integer::sum);
        warehouse.next = 34;
        warehouse.runs.tick();
        for (int i = 0; i < 34; i++) warehouse.runs.receivedPickup(receipt(box(i), WORLD, 0.5));
        warehouse.finish();
        assertEquals(List.of(34, 18), warehouse.deposits);
        assertEquals(PERSONAL, warehouse.backpack);
    }

    @Test
    void cleanupAndRestartOnlyTransferTheOwningPointsReceiptsOnce() {
        Map<String, Integer> backpack = new LinkedHashMap<>(PERSONAL);
        var layout = new InventorySlotFixture();
        var manager = new PlayerInventoryManager(() -> Optional.of(layout.snapshot(backpack)));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        manager.retainCollectionTasks(Set.of(first, second));
        manager.observe();
        for (var entry : PERSONAL.entrySet()) {
            InventorySlotFixture.lockFirst(manager,
                manager.view(false, false, Map.of()).revision(), entry.getKey(), true);
        }
        manager.recordPickup(first, box(0), 1);
        manager.recordPickup(second, box(0), 1);
        manager.observe();
        assertFalse(manager.hasCollectedInput(first));
        backpack.put(box(0), 3);
        backpack.put("minecraft:diamond", 1);
        manager.observe();
        var interrupted = manager.account(UUID.randomUUID());
        interrupted.usePickupReceipts(first);
        interrupted.setAcceptsClassification(false);
        manager.claimForCleanup(interrupted);
        assertEquals(Map.of(box(0), 1), interrupted.managedItems());
        manager.closeAccount(interrupted);
        var resumed = manager.account(UUID.randomUUID());
        resumed.usePickupReceipts(first);
        resumed.setAcceptsClassification(false);
        assertEquals(1, manager.claimUnexpected(resumed, Set.of()));
        assertEquals(0, manager.claimUnexpected(resumed, Set.of()));
        assertTrue(manager.hasCollectedInput(second));
        var diamond = manager.view(false, true, Map.of()).items().stream()
                .filter(item -> item.key().equals("minecraft:diamond")).findFirst().orElseThrow();
        assertEquals(0, diamond.protectedCount());
        assertEquals(1, diamond.classificationCount());
    }

    @Test
    void otherDimensionsAndPositionsWaitForClassification() {
        Warehouse warehouse = new Warehouse(0);
        warehouse.runs.receivedPickup(receipt(box(2), WORLD, 2.5));
        warehouse.runs.receivedPickup(receipt(box(3), "minecraft:the_nether", 0.5));
        warehouse.backpack.put(box(2), 1);
        warehouse.backpack.put(box(3), 1);
        for (int tick = 0; tick < 60; tick++) warehouse.runs.tick();
        assertTrue(warehouse.runs.runs().isEmpty());
        var items = warehouse.runs.playerInventory().items();
        assertEquals(2, items.stream().mapToInt(item -> item.protectedCount()).sum());
        assertEquals(2, items.stream().mapToInt(item -> item.classificationCount()).sum());
    }

    private static String box(int index) {
        return "minecraft:black_shulker_box#" + index + "@stowling:mixed_contents";
    }

    private static PickupReceipt receipt(String key, String dimension, double x) {
        return new PickupReceipt(UUID.randomUUID(), key, 1, dimension, x, 64.5, 0.5);
    }

    private static final class Warehouse
            implements DroppedItemObserverPort, NavigationPort, ContainerTransferPort {
        private final TaskRunManager runs;
        private final Map<String, Integer> backpack = new LinkedHashMap<>(PERSONAL);
        private final InventorySlotFixture slots =
                new InventorySlotFixture();
        private final Map<String, Integer> cached = new LinkedHashMap<>();
        private final List<Integer> deposits = new ArrayList<>();
        private final int total;
        private int next;
        private boolean atPickup;

        private Warehouse(int total) {
            this.total = total;
            var point = new CoordinatePoint(UUID.randomUUID(), "Input", 0, 64, 0, WORLD, 2);
            var region = new RegionDefinition(UUID.randomUUID(), "Cache", 10, 64, 0, 10, 64, 0);
            var chest = new ContainerTarget(new BlockPosition(10, 64, 0), "minecraft:chest");
            var task = new AutoClassifyDefinition(UUID.randomUUID(), "Collect", true, true,
                    false, "coordinate:" + point.id(), 2, "region:" + region.id(),
                    List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                    TaskPriority.URGENT);
            var base = TaskModuleRuntimeRegistry.unavailablePorts(this);
            var registry = new TaskModuleRuntimeRegistry(this, this,
                    (area, types, volume) -> ContainerScanResult.success(List.of(chest)), this,
                    base.containerWithdrawals(), base.categoryZones(),
                    StandardTaskModuleFactories.create());
            runs = new TaskRunManager(this, registry);
            runs.refreshTriggers(new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                    AutoClassifyTaskFactory.tasks(task, List.of()), List.of(region), List.of(),
                    AutoClassifyTaskFactory.triggers(task, List.of()), List.of(), List.of(task)));
            registry.inventory().observe();
            for (var entry : PERSONAL.entrySet()) {
                InventorySlotFixture.lockFirst(registry.inventory(),
                        runs.playerInventory().revision(), entry.getKey(), true);
            }
        }

        private void untilStarted() {
            for (int tick = 0; tick < 25 && !atPickup; tick++) runs.tick();
            assertTrue(atPickup);
        }

        private void pickupBatch() {
            int slots = backpack.values().stream().mapToInt(Integer::intValue).sum();
            while (slots++ < 36 && next < total) {
                String key = box(next++);
                runs.receivedPickup(receipt(key, WORLD, 0.5));
                backpack.merge(key, 1, Integer::sum);
            }
        }

        private void finish() {
            for (int tick = 0; tick < 500; tick++) {
                if (atPickup) pickupBatch();
                runs.tick();
                if (!runs.busy() && !runs.runs().isEmpty()) break;
            }
            assertFalse(runs.runs().isEmpty());
            assertEquals(TaskRunState.SUCCEEDED, runs.runs().getFirst().state(),
                    runs.runs().getFirst().message());
        }

        @Override
        public List<DroppedItem> observe(BlockPosition center, int radius) {
            return next == total ? List.of()
                    : List.of(new DroppedItem(new UUID(0, next), box(next), 1, POINT));
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return observe(POINT, 2);
        }

        @Override
        public Optional<PlayerInventorySnapshot> playerInventorySnapshot() {
            return Optional.of(slots.snapshot(backpack));
        }

        @Override
        public Optional<Map<String, Integer>> playerItemCounts() {
            return Optional.of(Map.copyOf(backpack));
        }

        @Override
        public boolean canPickUp(DroppedItem item) {
            return backpack.values().stream().mapToInt(Integer::intValue).sum() < 36;
        }

        @Override
        public NavigationStatus start(BlockPosition position) {
            atPickup = POINT.equals(position);
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition position) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus startInteraction(BlockPosition position, AreaBounds area) {
            return start(position);
        }

        @Override
        public void cancel() {
            atPickup = false;
        }

        @Override
        public ContainerTransferResult startDeposit(ContainerTarget target,
                Map<String, Integer> items) {
            items.forEach((key, count) -> {
                int remaining = backpack.get(key) - count;
                assertTrue(remaining >= 0);
                if (remaining == 0) backpack.remove(key);
                else backpack.put(key, remaining);
                cached.merge(key, count, Integer::sum);
            });
            deposits.add(items.values().stream().mapToInt(Integer::intValue).sum());
            return ContainerTransferResult.succeeded("deposited", Map.of());
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            throw new AssertionError("synchronous deposit");
        }

        @Override
        public void cancelDeposit() {}
    }
}
