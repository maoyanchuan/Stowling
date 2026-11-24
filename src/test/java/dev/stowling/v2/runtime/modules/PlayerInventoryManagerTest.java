package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.config.PlayerInventoryProtectionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerInventoryManagerTest {
    @Test
    void pickupUnloadingAdoptsAllPhysicalUnprotectedItemsWithoutChangingGlobalCounts() {
        var source = new MutableInventory();
        var manager = new PlayerInventoryManager(source);
        manager.initialize(new PlayerInventorySnapshot(Map.of("minecraft:gold_ingot", 2)));
        source.set(Map.of("minecraft:gold_ingot", 5, "minecraft:iron_ingot", 4,
                "minecraft:stone", 7));
        manager.observe();
        var previousTask = manager.account(UUID.randomUUID());
        manager.recordWithdrawn(previousTask, Map.of("minecraft:iron_ingot", 4));
        UUID task = UUID.randomUUID();
        manager.retainCollectionTasks(Set.of(task));
        var pickup = manager.account(UUID.randomUUID());
        pickup.usePickupReceipts(task);
        pickup.setAcceptsClassification(false);

        assertEquals(14, manager.claimAllUnprotectedForCollection(pickup));

        assertEquals(Map.of("minecraft:gold_ingot", 3, "minecraft:iron_ingot", 4,
                "minecraft:stone", 7), pickup.managedItems());
        assertTrue(previousTask.managedItems().isEmpty());
        assertEquals(source.snapshot().orElseThrow(), manager.expectedInventory().orElseThrow());
        assertEquals(0, manager.claimAllUnprotectedForCollection(pickup));
    }

    @Test
    void aLateReceiptForAlreadyClaimedPhysicalCargoDoesNotBlockOrDoubleBookUnloading() {
        var source = new MutableInventory();
        var manager = new PlayerInventoryManager(source);
        manager.observe();
        UUID task = UUID.randomUUID();
        manager.retainCollectionTasks(Set.of(task));
        var pickup = manager.account(UUID.randomUUID());
        pickup.usePickupReceipts(task);
        source.set(Map.of("minecraft:gold_ingot", 5));
        manager.observe();
        assertEquals(5, manager.claimAllUnprotectedForCollection(pickup));

        manager.recordPickup(task, "minecraft:gold_ingot", 5);
        manager.observe();

        assertEquals(0, manager.pendingPickupCount(pickup));
        assertEquals(0, manager.claimAllUnprotectedForCollection(pickup));
        assertEquals(Map.of("minecraft:gold_ingot", 5), pickup.managedItems());
        assertEquals(source.snapshot().orElseThrow(), manager.expectedInventory().orElseThrow());
    }

    @Test
    void unloadingNeverUsesProtectedStockToReplaceMissingTaskCargo() {
        var source = new MutableInventory();
        var manager = new PlayerInventoryManager(source);
        manager.initialize(new PlayerInventorySnapshot(Map.of("minecraft:gold_ingot", 2)));
        source.set(Map.of("minecraft:gold_ingot", 2));
        manager.observe();
        var pickup = manager.account(UUID.randomUUID());
        pickup.recordCollected("minecraft:gold_ingot", 3);

        manager.claimAllUnprotectedForCollection(pickup);

        assertTrue(pickup.managedItems().isEmpty());
        assertEquals(Map.of("minecraft:gold_ingot", 2),
                manager.expectedInventory().orElseThrow().itemCounts());
    }

    @Test
    void recoveryBorrowsOneStackFromItsOwnInputWithoutClaimingTheWholeFullBackpack() {
        String wood = "minecraft:oak_slab";
        var source = new MutableInventory();
        var inventory = new PlayerInventoryManager(source);
        inventory.observe();
        UUID collectionTask = UUID.randomUUID();
        inventory.retainCollectionTasks(Set.of(collectionTask));
        source.set(Map.of(wood, 2176));
        inventory.recordPickup(collectionTask, wood, 2176);
        inventory.observe();
        var recovery = inventory.account(UUID.randomUUID());
        recovery.setRecoveryInputTask(collectionTask);
        var before = inventory.expectedInventory().orElseThrow();

        assertEquals(64, inventory.claimRecoveryInput(recovery, wood, 64));
        assertEquals(0, inventory.claimRecoveryInput(recovery, wood, 64));
        assertEquals(Map.of(wood, 64), recovery.managedItems());
        assertEquals(before, inventory.expectedInventory().orElseThrow());
        recovery.confirmDeposit("managed", Map.of(wood, 64), Map.of());
        source.set(Map.of(wood, 2112));
        inventory.observe();
        inventory.closeAccount(recovery);

        var next = inventory.account(UUID.randomUUID());
        next.usePickupReceipts(collectionTask);
        assertEquals(2112, inventory.claimCollectionItems(next, Set.of()));
        assertEquals(Map.of(wood, 2112), next.managedItems());
        assertFalse(inventory.hasAbandonedAccounts());
    }

    @Test
    void recoveryCannotBorrowProtectedItemsOrAnotherCollectionPointsReceipts() {
        String wood = "minecraft:oak_slab";
        var source = new MutableInventory();
        source.set(Map.of(wood, 64));
        var inventory = new PlayerInventoryManager(source);
        source.snapshot().ifPresent(inventory::initialize);
        inventory.observe();
        UUID ownPoint = UUID.randomUUID();
        UUID otherPoint = UUID.randomUUID();
        inventory.retainCollectionTasks(Set.of(ownPoint, otherPoint));
        inventory.recordPickup(otherPoint, wood, 64);
        source.set(Map.of(wood, 128));
        inventory.observe();
        var recovery = inventory.account(UUID.randomUUID());
        recovery.setRecoveryInputTask(ownPoint);

        assertEquals(0, inventory.claimRecoveryInput(recovery, wood, 64));
        assertTrue(recovery.managedItems().isEmpty());
        assertTrue(inventory.hasCollectedInput(otherPoint));
        assertEquals(128, inventory.expectedInventory().orElseThrow().count(wood));
    }

    @Test
    void recoveryDoesNotBorrowAPartialStackThatCannotFreeAnInventorySlot() {
        String wood = "minecraft:oak_slab";
        var source = new MutableInventory();
        var inventory = new PlayerInventoryManager(source);
        inventory.observe();
        UUID collectionTask = UUID.randomUUID();
        inventory.retainCollectionTasks(Set.of(collectionTask));
        inventory.recordPickup(collectionTask, wood, 16);
        source.set(Map.of(wood, 16));
        inventory.observe();
        var recovery = inventory.account(UUID.randomUUID());
        recovery.usePickupReceipts(collectionTask);

        assertEquals(0, inventory.claimRecoveryInput(recovery, wood, 64));
        assertTrue(recovery.managedItems().isEmpty());
        assertEquals(16, inventory.claimRecoveryInput(recovery, wood, 16));
        assertFalse(inventory.hasCollectedInput(collectionTask));
    }

    @Test
    void inlineSortingReturnsItsCargoToTheCollectingAccountWithoutChangingGlobalCounts() {
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of("minecraft:diamond_pickaxe", 1));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount collecting = manager.account(UUID.randomUUID());
        PlayerInventoryAccount sorting = manager.account(UUID.randomUUID());
        collecting.recordCollected("minecraft:stone", 64);
        sorting.recordWithdrawn("minecraft:gold_ingot", 32);
        var before = manager.expectedInventory().orElseThrow();

        manager.transferManaged(sorting, collecting);
        manager.closeAccount(sorting);

        assertEquals(before, manager.expectedInventory().orElseThrow());
        assertTrue(sorting.managedItems().isEmpty());
        assertEquals(Map.of("minecraft:stone", 64, "minecraft:gold_ingot", 32),
                collecting.managedItems());
        assertFalse(manager.hasAbandonedAccounts());
    }

    @Test
    void settledCleanupRemovesPhantomRemainderWithoutCountingItAsDelivery() {
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of("minecraft:diamond_pickaxe", 1));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        task.recordWithdrawn("minecraft:stone", 1293);
        task.confirmDeposit("carried", Map.of("minecraft:stone", 1292), Map.of());
        manager.observe();

        assertEquals(Map.of("minecraft:stone", 1), manager.reconcileMissingItems(task));
        assertTrue(task.managedItems().isEmpty());
        assertTrue(task.itemsForSource("carried").isEmpty());
        assertEquals(Map.of("minecraft:diamond_pickaxe", 1),
                manager.expectedInventory().orElseThrow().itemCounts());
    }

    @Test
    void cleanupKeepsRealTaskItemsAndReservesPersonalAndOtherTaskItems() {
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of("minecraft:stone", 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        PlayerInventoryAccount other = manager.account(UUID.randomUUID());
        other.recordWithdrawn("minecraft:stone", 3);
        task.recordCollected("minecraft:stone", 10);
        inventory.set(Map.of("minecraft:stone", 14));
        manager.observe();

        assertEquals(Map.of("minecraft:stone", 4), manager.reconcileMissingItems(task));
        assertEquals(Map.of("minecraft:stone", 6), task.itemsForSource("collected"));
        assertEquals(Map.of("minecraft:stone", 3), other.managedItems());
        assertEquals(14, manager.expectedInventory().orElseThrow().count("minecraft:stone"));
        assertTrue(manager.reconcileMissingItems(task).isEmpty());
    }

    @Test
    void unavailableInventoryCannotEraseTaskReceipts() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        task.recordWithdrawn("minecraft:stone", 64);
        inventory.unavailable();
        manager.observe();

        assertThrows(IllegalStateException.class, () -> manager.reconcileMissingItems(task));
        assertEquals(Map.of("minecraft:stone", 64), task.managedItems());
    }

    @Test
    void lateDropsKeepTheirOwnerWhileOtherIdleGiftsWaitForClassification() {
        MutableInventory inventory = new MutableInventory();
        MutableClock clock = new MutableClock();
        String tool = "minecraft:diamond_pickaxe";
        String wood = "minecraft:oak_log";
        inventory.set(Map.of(tool, 1));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory, clock);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount collection = manager.account(UUID.randomUUID());
        collection.beginCollection(Set.of());
        inventory.set(Map.of(tool, 1, wood, 64));
        manager.observe();
        assertEquals(64, manager.claimCollectionItems(collection, Set.of()));
        collection.confirmDeposit("collected", Map.of(wood, 64), Map.of());
        inventory.set(Map.of(tool, 1));
        manager.observe();
        manager.closeAccount(collection);

        inventory.set(Map.of(tool, 1, wood, 2240, "minecraft:iron_pickaxe", 1));
        manager.observe();
        manager.reconcileUnassignedItems();

        assertTrue(manager.needsRecovery());
        PlayerInventoryAccount next = manager.account(UUID.randomUUID());
        next.setAcceptsClassification(false);
        assertEquals(2240, manager.claimUnexpected(next, Set.of()));
        assertEquals(Map.of(wood, 2240), next.managedItems());
        assertEquals(1, manager.view(false, true, Map.of()).items().stream()
                .filter(item -> item.itemId().equals("minecraft:iron_pickaxe"))
                .findFirst().orElseThrow().classificationCount());
    }

    @Test
    void latePickupWindowExpiresAndNewGiftsEnterTheClassificationQueue() {
        MutableInventory inventory = new MutableInventory();
        MutableClock clock = new MutableClock();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory, clock);
        manager.observe();
        PlayerInventoryAccount collection = manager.account(UUID.randomUUID());
        collection.beginCollection(Set.of());
        inventory.set(Map.of("minecraft:stone", 64));
        manager.observe();
        manager.claimCollectionItems(collection, Set.of());
        collection.confirmDeposit("collected", Map.of("minecraft:stone", 64), Map.of());
        inventory.set(Map.of());
        manager.observe();
        manager.closeAccount(collection);
        clock.now = clock.now.plusSeconds(3);
        inventory.set(Map.of("minecraft:stone", 2));
        manager.observe();
        manager.reconcileUnassignedItems();

        assertFalse(manager.hasAbandonedAccounts());
        assertEquals(2,
                manager.view(false, true, Map.of()).items().getFirst().classificationCount());
    }

    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-11-19T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }
    @Test
    void continuousObservedPickupsAndLateArrivalsAreBookedWithoutAnEntityCountCap() {
        String disc = "minecraft:music_disc_otherside";
        String tool = "minecraft:diamond_pickaxe";
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of(disc, 2, tool, 1));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        task.setAcceptsClassification(false);
        task.beginCollection(Set.of(disc));

        inventory.set(Map.of(disc, 34, tool, 1));
        manager.observe();
        assertEquals(32, manager.claimCollectionItems(task, Set.of()));
        assertEquals(0, manager.claimCollectionItems(task, Set.of()));

        // Deposit the first batch; a further disc is picked up on the way to the cache.
        task.confirmDeposit("collected", Map.of(disc, 32), Map.of());
        inventory.set(Map.of(disc, 3, tool, 2));
        manager.observe();
        assertEquals(1, manager.claimUnexpected(task, Set.of()));
        assertEquals(Map.of(disc, 1), task.managedItems());
        assertEquals(Map.of(disc, 3, tool, 2), manager.expectedInventory()
                .orElseThrow().itemCounts());
        assertEquals(0, manager.claimUnexpected(task, Set.of()));
    }

    @Test
    void pickupClaimsKeepExactIdentityAndTheRequestedFilter() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        task.beginCollection(Set.of("minecraft:stone#first"));
        inventory.set(Map.of("minecraft:stone#first", 8, "minecraft:stone#other", 4));
        manager.observe();

        assertEquals(0, manager.claimCollectionItems(task, Set.of("minecraft:dirt")));
        assertEquals(8, manager.claimCollectionItems(task, Set.of("minecraft:stone")));
        assertEquals(Map.of("minecraft:stone#first", 8), task.managedItems());
    }

    @Test
    void allItemCollectionClaimsEveryUnprotectedKindWithoutRequiringAnObservedEntity() {
        String disc = "minecraft:music_disc_otherside";
        String tool = "minecraft:diamond_pickaxe";
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of(tool, 1, disc, 2));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        task.beginCollection(Set.of());
        PlayerInventoryAccount other = manager.account(UUID.randomUUID());
        other.recordWithdrawn("minecraft:stone", 4);
        inventory.set(Map.of(tool, 1, disc, 34, "minecraft:stone", 7));
        manager.observe();

        assertEquals(35, manager.claimCollectionItems(task, Set.of()));
        assertEquals(Map.of(disc, 32, "minecraft:stone", 3), task.managedItems());
        assertEquals(Map.of("minecraft:stone", 4), other.managedItems());
        assertEquals(0, manager.claimUnexpected(task, Set.of()));
    }

    @Test
    void cleanupStoresUnprotectedItemsEvenWithoutAPreviousCollectionAction() {
        String tool = "minecraft:diamond_pickaxe";
        String disc = "minecraft:music_disc_otherside";
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of(tool, 1, disc, 2));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.snapshot().ifPresent(manager::initialize);
        manager.observe();
        PlayerInventoryAccount cleanup = manager.account(UUID.randomUUID());
        inventory.set(Map.of(tool, 1, disc, 34));
        manager.observe();

        assertEquals(32, manager.claimUnprotectedItems(cleanup));
        assertEquals(Map.of(disc, 32), cleanup.managedItems());
        assertEquals(0, manager.claimUnprotectedItems(cleanup));
        assertEquals(Map.of(tool, 1, disc, 34), manager.expectedInventory()
                .orElseThrow().itemCounts());
    }

    @Test
    void pickaxeGivenBeforeTaskStartWaitsForClassificationWithoutAWebLock() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        manager.observe();
        inventory.set(Map.of("minecraft:diamond_pickaxe#undamaged", 1));
        manager.observe();
        manager.reconcileUnassignedItems();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        task.setAcceptsClassification(false);

        assertEquals(0, manager.claimUnexpected(task, Set.of()));
        assertTrue(task.managedItems().isEmpty());
        assertTrue(manager.needsRecovery());
        var item = manager.view(false, false, Map.of()).items().getFirst();
        assertEquals(0, item.protectedCount());
        assertEquals(1, item.classificationCount());
    }

    @Test
    void sameItemInLockedAndUnlockedPositionsIsHandledSeparately() {
        var source = new AtomicReference<>(slots(Map.of(0, 16, 12, 48)));
        var manager = new PlayerInventoryManager(() -> Optional.of(source.get()));
        manager.observe();
        manager.setLocked(manager.view(false, true, Map.of()).revision(), 0, true);
        assertEquals(Map.of("minecraft:stone", 48), manager.unlockedItems());
        assertEquals(Map.of("minecraft:stone", 16), manager.protectedItemCounts());
        var task = manager.account(UUID.randomUUID());
        manager.claimAllUnprotectedForCollection(task);
        assertEquals(Map.of("minecraft:stone", 48), task.managedItems());
        assertEquals(36, manager.view(false, true, Map.of()).slots().size());
    }

    @Test
    void movingContentsLeavesTheLockOnItsOriginalEmptyPosition() {
        var source = new AtomicReference<>(slots(Map.of(0, 16)));
        var manager = new PlayerInventoryManager(() -> Optional.of(source.get()));
        manager.observe();
        manager.setLocked(manager.view(false, true, Map.of()).revision(), 0, true);
        String revision = manager.view(false, true, Map.of()).revision();
        source.set(slots(Map.of(12, 16)));
        manager.observe();
        assertEquals(Set.of(0), manager.lockedSlots());
        assertEquals(Map.of("minecraft:stone", 16), manager.unlockedItems());
        assertTrue(manager.protectedItemCounts().isEmpty());
        assertThrows(IllegalStateException.class, () -> manager.setLocked(revision, 12, true));
    }

    @Test
    void emptyLocksSurviveRestartReloginAndNewItemIdentity(@TempDir Path directory)
            throws IOException {
        var store = new PlayerInventoryProtectionStore(directory.resolve("locks.json"));
        var source = new AtomicReference<>(slots(Map.of()));
        var first = new PlayerInventoryManager(() -> Optional.of(source.get()));
        first.configureProtection(store.load(), store::save);
        first.observe();
        first.setLocked(first.view(false, false, Map.of()).revision(), 8, true);
        var restarted = new PlayerInventoryManager(() -> Optional.of(source.get()));
        restarted.configureProtection(store.load(), store::save);
        restarted.observe();
        restarted.reset();
        source.set(slots(Map.of(8, 64, 9, 2)));
        restarted.observe();
        assertEquals(Set.of(8), restarted.lockedSlots());
        assertEquals(Map.of("minecraft:stone", 2), restarted.unlockedItems());
        assertEquals(2, store.load().schemaVersion());
        assertTrue(store.load().itemCounts().isEmpty());
    }

    @Test
    void migrationWaitsForCompleteSnapshotAndLocksEveryMatchingStack(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve("locks.json");
        String legacy = "{\"itemCounts\":{\"minecraft:stone\":4}}";
        Files.writeString(file, legacy);
        var store = new PlayerInventoryProtectionStore(file);
        var source = new AtomicReference<>(new PlayerInventorySnapshot(Map.of()));
        var manager = new PlayerInventoryManager(() -> Optional.of(source.get()));
        manager.configureProtection(store.load(), store::save);
        manager.observe();
        assertEquals(legacy, Files.readString(file));
        source.set(slots(Map.of(0, 32, 12, 64)));
        manager.observe();
        assertEquals(Set.of(0, 12), manager.lockedSlots());
        assertEquals(Map.of("minecraft:stone", 96), manager.protectedItemCounts());
        assertEquals(legacy, Files.readString(directory.resolve("locks.json.v1.bak")));
        assertTrue(store.load().itemCounts().isEmpty());
    }

    @Test
    void changingCountOrDurabilityDoesNotMoveOrRemoveTheLock() {
        var source = new AtomicReference<>(slots(Map.of(3, 64)));
        var manager = new PlayerInventoryManager(() -> Optional.of(source.get()));
        manager.observe();
        manager.setLocked(manager.view(false, true, Map.of()).revision(), 3, true);
        source.set(slots(Map.of(3, 1)));
        manager.observe();
        manager.consumeProtectedFood("minecraft:stone", 63);
        assertEquals(Set.of(3), manager.lockedSlots());
        var changed = new java.util.ArrayList<>(slots(Map.of()).slots());
        String worn = "minecraft:diamond_pickaxe#worn";
        changed.set(3, new PlayerInventorySlot(3, worn, "minecraft:diamond_pickaxe", 1, 1,
                List.of(), 10, 100));
        source.set(new PlayerInventorySnapshot(Map.of(worn, 1), Map.of(), Map.of(), changed));
        manager.observe();
        assertEquals(Map.of(worn, 1), manager.protectedItemCounts());
        source.set(slots(Map.of()));
        manager.observe();
        assertEquals(Set.of(3), manager.lockedSlots());
        assertThrows(IllegalArgumentException.class,
                () -> manager.setLocked(manager.view(false, true, Map.of()).revision(), 36, true));
    }

    @Test
    void refillLocksOnlyTheReportedFoodDestinationsAndConsumptionKeepsThemLocked() {
        var source = new AtomicReference<>(slots(Map.of(2, 5, 12, 20)));
        var manager = new PlayerInventoryManager(() -> Optional.of(source.get()));
        manager.observe();
        manager.reserveAcquiredFood(Set.of(2));
        assertEquals(Set.of(2), manager.lockedSlots());
        assertEquals(Map.of("minecraft:stone", 20), manager.unlockedItems());
        source.set(slots(Map.of(12, 20)));
        manager.consumeProtectedFood("minecraft:stone", 5);
        assertEquals(Set.of(2), manager.lockedSlots());
    }

    private static PlayerInventorySnapshot slots(Map<Integer, Integer> contents) {
        List<PlayerInventorySlot> slots = new java.util.ArrayList<>();
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 36; index++) {
            int count = contents.getOrDefault(index, 0);
            slots.add(count == 0 ? PlayerInventorySlot.empty(index)
                    : new PlayerInventorySlot(index, "minecraft:stone", "minecraft:stone", count,
                            64, List.of(), 0, 0));
            if (count > 0) counts.merge("minecraft:stone", count, Integer::sum);
        }
        return new PlayerInventorySnapshot(counts, Map.of(), Map.of(), slots);
    }

    @Test
    void firstObservationEstablishesBaselineWithoutChanges() {
        PlayerInventoryManager manager = manager(
                Map.of("minecraft:iron_ingot", 4)
        );

        PlayerInventoryManager.Observation observation =
                manager.observe().orElseThrow();

        assertTrue(observation.baseline());
        assertEquals(Map.of("minecraft:iron_ingot", 4), observation.snapshot()
                .itemCounts());
        assertFalse(observation.delta().changed());
    }

    @Test
    void calculatesExactAdditionsAndRemovals() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.set(Map.of(
                "minecraft:iron_ingot", 4,
                "minecraft:gold_ingot", 2
        ));
        manager.observe().orElseThrow();
        inventory.set(Map.of(
                "minecraft:iron_ingot", 1,
                "minecraft:gold_ingot", 7,
                "minecraft:diamond", 2
        ));

        PlayerInventoryManager.Observation observation =
                manager.observe().orElseThrow();

        assertEquals(
                Map.of(
                        "minecraft:gold_ingot", 5,
                        "minecraft:diamond", 2
                ),
                observation.delta().added()
        );
        assertEquals(
                Map.of("minecraft:iron_ingot", 3),
                observation.delta().removed()
        );
    }

    @Test
    void resetStartsANewBaseline() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.set(Map.of("minecraft:iron_ingot", 4));
        manager.observe().orElseThrow();
        inventory.set(Map.of("minecraft:iron_ingot", 8));
        manager.observe().orElseThrow();

        manager.reset();
        PlayerInventoryManager.Observation observation =
                manager.observe().orElseThrow();

        assertTrue(observation.baseline());
        assertFalse(observation.delta().changed());
    }

    @Test
    void unavailableInventoryDoesNotDiscardPreviousBaseline() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.set(Map.of("minecraft:iron_ingot", 4));
        manager.observe().orElseThrow();

        inventory.unavailable();

        assertTrue(manager.observe().isEmpty());
        assertEquals(
                Map.of("minecraft:iron_ingot", 4),
                manager.current().orElseThrow().itemCounts()
        );
    }

    private static PlayerInventoryManager manager(
            Map<String, Integer> initial
    ) {
        AtomicReference<PlayerInventorySnapshot> snapshot =
                new AtomicReference<>(new PlayerInventorySnapshot(initial));
        return new PlayerInventoryManager(() ->
                Optional.ofNullable(snapshot.get()));
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
            set(new PlayerInventorySnapshot(counts));
        }

        private void set(PlayerInventorySnapshot inventory) {
            snapshot = Optional.of(inventory);
        }

        private void unavailable() {
            snapshot = Optional.empty();
        }
    }
}
