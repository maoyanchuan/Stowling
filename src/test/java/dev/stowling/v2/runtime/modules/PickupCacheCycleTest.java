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
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskFlowTemplates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickupCacheCycleTest {
    private static final String DISC = "minecraft:music_disc_otherside";
    private static final String TOOL = "minecraft:diamond_pickaxe";
    private static final String STONE = "minecraft:stone";
    private static final BlockPosition PICKUP = new BlockPosition(0, 64, 0);
    private static final ContainerTarget NEAR = new ContainerTarget(PICKUP, "minecraft:chest");
    private static final ContainerTarget FAR = new ContainerTarget(
            new BlockPosition(10, 64, 0), "minecraft:chest");

    @Test
    void fillsTheBackpackBeforeUnloadingAndReusesOnePositionScanAcrossBatches() {
        Scenario scenario = new Scenario(11);

        TaskModuleResult result = scenario.run();

        assertEquals(TaskModuleState.SUCCEEDED, result.state(), result.message());
        assertEquals(List.of(4, 4, 3), scenario.discDeposits());
        assertEquals(1, scenario.scans);
        assertEquals(0, scenario.remaining);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
        assertTrue(scenario.workspace.managedItems().isEmpty());
        assertTrue(scenario.ticks < 60, "no repeated 20/40 tick settlement waits");
    }

    @Test
    void storesPreexistingUnprotectedKindsAndKeepsTheProtectedPartOfTheSameItem() {
        Scenario scenario = new Scenario(1);
        scenario.items.put(STONE, 7);

        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());

        assertEquals(Map.of(DISC, 1, STONE, 7), scenario.requests.getFirst());
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void aFullBackpackWithoutAnOwnPickupReceiptCanUnloadUnprotectedItems() {
        Scenario scenario = new Scenario(2);
        scenario.items.put(DISC, 6);

        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());

        assertEquals(List.of(4, 2), scenario.discDeposits());
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void reachableCacheStaysOpenForLatePickupsWithoutAnySeparateContainerMove() {
        Scenario scenario = new Scenario(5);
        scenario.inPlace = true;
        scenario.latePickup = true;
        scenario.confirmationTicks = 3;

        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());

        assertEquals(List.of(4, 1), scenario.discDeposits());
        assertEquals(List.of(NEAR, NEAR), scenario.targets);
        assertEquals(1, scenario.opens);
        assertEquals(0, scenario.containerMoves);
        assertFalse(scenario.open);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void usesPartialCapacityThenSwitchesToTheNextCache() {
        Scenario scenario = new Scenario(4);
        scenario.inPlace = true;
        scenario.capacity.put(NEAR, 3);

        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());

        assertEquals(List.of(NEAR, FAR), scenario.targets);
        assertEquals(List.of(3, 1), scenario.discDeposits());
        assertEquals(1, scenario.containerMoves);
        assertEquals(1, scenario.scans);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void allFullCachesFailWithoutResumingPickupOrLosingTheCarriedBatch() {
        Scenario scenario = new Scenario(11);
        scenario.capacity.put(NEAR, 0);
        scenario.capacity.put(FAR, 0);

        TaskModuleResult result = scenario.run();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("所有缓存均已满"));
        assertEquals(7, scenario.remaining);
        assertEquals(Map.of(DISC, 4), scenario.workspace.managedItems());
        assertEquals(2, scenario.targets.size());
        assertEquals(1, scenario.scans);
    }

    @Test
    void exactlyFilledCacheIsSkippedOnEveryFollowingUnloadInTheSameTask() {
        Scenario scenario = new Scenario(11);
        scenario.inPlace = true;
        scenario.capacity.put(NEAR, 4);

        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());

        assertEquals(List.of(NEAR, FAR, FAR), scenario.targets);
        assertEquals(List.of(4, 4, 3), scenario.discDeposits());
        assertEquals(1, scenario.scans);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void fillingAllCachesStopsBeforeCollectingAnotherBatch() {
        Scenario scenario = new Scenario(11);
        scenario.inPlace = true;
        scenario.capacity.put(NEAR, 4);
        scenario.capacity.put(FAR, 4);

        TaskModuleResult result = scenario.run();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("所有缓存均已满"));
        assertEquals(List.of(NEAR, FAR), scenario.targets);
        assertEquals(List.of(4, 4), scenario.discDeposits());
        assertEquals(3, scenario.remaining);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
        assertTrue(scenario.workspace.managedItems().isEmpty());
        assertFalse(scenario.open);
    }

    @Test
    void fillingTheLastCacheWithTheLastDropCompletesInsteadOfReportingFull() {
        Scenario scenario = new Scenario(8);
        scenario.inPlace = true;
        scenario.capacity.put(NEAR, 4);
        scenario.capacity.put(FAR, 4);

        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());

        assertEquals(List.of(NEAR, FAR), scenario.targets);
        assertEquals(0, scenario.remaining);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void staleReceiptBalanceDoesNotKeepAnEmptyAreaRunning() {
        Scenario scenario = new Scenario(0);
        scenario.inventory.recordPickup(scenario.taskId, DISC, 1);
        assertEquals(TaskModuleState.SUCCEEDED, scenario.run().state());
        assertEquals(0, scenario.opens);
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
    }

    @Test
    void stoppingDuringADepositWaitsForItsFixedBatchToBeConfirmed() {
        Scenario scenario = new Scenario(8);
        scenario.confirmationTicks = 4;
        while (scenario.pending == null) scenario.tick();

        scenario.cycle.requestStop();
        assertFalse(scenario.cycle.safeToStop());
        TaskModuleResult result = scenario.run();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(4, scenario.remaining);
        assertEquals(List.of(4), scenario.discDeposits());
        assertEquals(Map.of(DISC, 2, TOOL, 1), scenario.items);
        assertFalse(scenario.open);
    }

    private static final class Scenario
            implements NavigationPort, DroppedItemObserverPort, ContainerTransferPort {
        private final UUID taskId = UUID.randomUUID();
        private final Map<String, Integer> items = new LinkedHashMap<>(Map.of(DISC, 2, TOOL, 1));
        private final Map<ContainerTarget, Integer> capacity = new LinkedHashMap<>();
        private final List<Map<String, Integer>> requests = new ArrayList<>();
        private final List<Map<String, Integer>> deposits = new ArrayList<>();
        private final List<ContainerTarget> targets = new ArrayList<>();
        private final PlayerInventoryManager inventory;
        private final TaskRunWorkspace workspace;
        private final PickupCacheCycle cycle;
        private int remaining;
        private int scans;
        private int ticks;
        private int opens;
        private int containerMoves;
        private int confirmationTicks;
        private int confirmationRemaining;
        private boolean atPickup;
        private boolean inPlace;
        private boolean latePickup;
        private boolean open;
        private ContainerTarget opened;
        private Map<String, Integer> pending;

        private Scenario(int remaining) {
            this.remaining = remaining;
            inventory = new PlayerInventoryManager(this::playerInventorySnapshot);
            inventory.configureProtection(PlayerInventorySnapshot.locks(Set.of(0, 1)), ignored -> {});
            inventory.observe();
            inventory.retainCollectionTasks(Set.of(taskId));
            var account = inventory.account(UUID.randomUUID());
            account.usePickupReceipts(taskId);
            account.setAcceptsClassification(false);
            workspace = new TaskRunWorkspace(account);
            var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
            var region = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 10, 64, 0);
            var ports = TaskModuleRuntimeRegistry.unavailablePorts(this);
            var context = new TaskExecutionContext(this, this, (area, types, volume) -> {
                scans++;
                return ContainerScanResult.success(List.of(FAR, NEAR));
            }, this, ports.containerWithdrawals(), ports.containerInspections(),
                    ports.categoryMarkerCache(), ports.foodConsumption(), ports.categoryZones(),
                    Map.of(point.id(), point), Map.of(region.id(), region), Map.of(), workspace,
                    inventory);
            var step = TaskFlowTemplates.pickupToCache("coordinate:" + point.id(),
                    "region:" + region.id(), Map.of()).getFirst();
            cycle = (PickupCacheCycle) ports.create(step, context);
        }

        private TaskModuleResult tick() {
            ticks++;
            if (atPickup && !open && remaining > 0 && items.get(DISC) < 6) gain();
            return cycle.tick();
        }

        private TaskModuleResult run() {
            for (int tick = 0; tick < 200; tick++) {
                TaskModuleResult result = tick();
                if (result.state() == TaskModuleState.SUCCEEDED
                        || result.state() == TaskModuleState.FAILED) return result;
            }
            throw new AssertionError("pickup cycle did not terminate");
        }

        private List<Integer> discDeposits() {
            return deposits.stream().map(batch -> batch.getOrDefault(DISC, 0)).toList();
        }

        private void gain() {
            remaining--;
            items.merge(DISC, 1, Integer::sum);
            inventory.recordPickup(taskId, DISC, 1);
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
        public boolean isWithinInteractionRange(BlockPosition target) {
            return inPlace && target.equals(PICKUP);
        }

        @Override
        public void cancel() {
            atPickup = false;
        }

        @Override
        public List<DroppedItem> observe(BlockPosition center, int radius) {
            return remaining == 0 ? List.of() : List.of(new DroppedItem(
                    new UUID(0, remaining), DISC, 1, PICKUP));
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return observe(PICKUP, 1);
        }

        @Override
        public boolean canPickUp(DroppedItem item) {
            return items.get(DISC) < 6;
        }

        @Override
        public Optional<PlayerInventorySnapshot> playerInventorySnapshot() {
            List<dev.stowling.automation.PlayerInventorySlot> slots = new ArrayList<>();
            for (int index = 0; index < 36; index++) {
                slots.add(dev.stowling.automation.PlayerInventorySlot.empty(index));
            }
            slots.set(0, slot(0, DISC, 2));
            slots.set(1, slot(1, TOOL, 1));
            int unlocked = items.getOrDefault(DISC, 0) - 2;
            if (unlocked > 0) slots.set(2, slot(2, DISC, unlocked));
            if (items.containsKey(STONE)) slots.set(3, slot(3, STONE, items.get(STONE)));
            return Optional.of(new PlayerInventorySnapshot(items, Map.of(), Map.of(), slots));
        }

        private static dev.stowling.automation.PlayerInventorySlot slot(int index, String key,
                int count) {
            return new dev.stowling.automation.PlayerInventorySlot(index, key, key, count,
                    64, List.of(), 0, 0);
        }

        @Override
        public Optional<Map<String, Integer>> playerItemCounts() {
            return Optional.of(Map.copyOf(items));
        }

        @Override
        public ContainerTransferResult startDeposit(ContainerTarget target,
                Map<String, Integer> cargo) {
            throw new AssertionError("pickup unloading must allow in-place deposits");
        }

        @Override
        public ContainerTransferResult startPickupCacheDeposit(ContainerTarget target,
                Map<String, Integer> cargo) {
            assertTrue(pending == null, "never reset an unconfirmed batch");
            if (!open || !target.equals(opened)) {
                opens++;
                if (!isWithinInteractionRange(target.position())) containerMoves++;
            }
            opened = target;
            open = true;
            atPickup = false;
            targets.add(target);
            requests.add(Map.copyOf(cargo));
            pending = cargo;
            confirmationRemaining = confirmationTicks;
            return confirmationTicks == 0 ? completeDeposit()
                    : ContainerTransferResult.pending("server confirmation", cargo);
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            if (--confirmationRemaining > 0) {
                return ContainerTransferResult.pending("server confirmation", pending);
            }
            return completeDeposit();
        }

        @Override
        public boolean isPickupCacheFull(ContainerTarget target) {
            return pending == null && open && target.equals(opened)
                    && capacity.getOrDefault(target, 10000) == 0;
        }

        private ContainerTransferResult completeDeposit() {
            int space = capacity.getOrDefault(opened, 10000);
            Map<String, Integer> left = new LinkedHashMap<>(pending);
            Map<String, Integer> moved = new LinkedHashMap<>();
            for (var entry : pending.entrySet()) {
                int count = Math.min(space, entry.getValue());
                if (count == 0) continue;
                moved.put(entry.getKey(), count);
                items.compute(entry.getKey(), (key, before) -> before - count);
                left.put(entry.getKey(), entry.getValue() - count);
                space -= count;
            }
            left.values().removeIf(count -> count == 0);
            items.values().removeIf(count -> count == 0);
            capacity.put(opened, space);
            deposits.add(Map.copyOf(moved));
            pending = null;
            if (latePickup && remaining > 0) {
                latePickup = false;
                gain();
            }
            return ContainerTransferResult.succeeded("confirmed", left);
        }

        @Override
        public void cancelDeposit() {
            assertTrue(pending == null, "cannot close an unconfirmed batch");
            open = false;
            opened = null;
        }
    }
}
