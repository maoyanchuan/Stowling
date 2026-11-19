package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlayerInventoryManagerTest {
    @Test
    void lateDropsBetweenTasksStayOwnedWhileUnrelatedIdleGiftsRemainProtected() {
        MutableInventory inventory = new MutableInventory();
        MutableClock clock = new MutableClock();
        String tool = "minecraft:diamond_pickaxe";
        String wood = "minecraft:oak_log";
        inventory.set(Map.of(tool, 1));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory, clock);
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
        manager.protectUntrackedItems();

        assertTrue(manager.needsRecovery());
        PlayerInventoryAccount next = manager.account(UUID.randomUUID());
        assertEquals(2240, manager.claimUnexpected(next, Set.of()));
        assertEquals(Map.of(wood, 2240), next.managedItems());
        assertEquals(1, manager.view(false, true, Map.of()).items().stream()
                .filter(item -> item.itemId().equals("minecraft:iron_pickaxe"))
                .findFirst().orElseThrow().protectedCount());
    }

    @Test
    void latePickupWindowExpiresAndDoesNotReclassifyPersonalGiftsIndefinitely() {
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
        manager.protectUntrackedItems();

        assertFalse(manager.needsRecovery());
        assertEquals(2, manager.view(false, true, Map.of()).items().getFirst().protectedCount());
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
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
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
    void pickaxeGivenBeforeTaskStartIsProtectedWithoutAnyManualSetting() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        manager.observe();
        inventory.set(Map.of("minecraft:diamond_pickaxe#undamaged", 1));
        manager.observe();
        manager.protectUntrackedItems();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());

        assertEquals(0, manager.claimUnexpected(task, Set.of()));
        assertTrue(task.managedItems().isEmpty());
        assertFalse(manager.needsRecovery());
        var item = manager.view(false, false, Map.of()).items().getFirst();
        assertEquals(1, item.protectedCount());
        assertEquals(0, item.classificationCount());
    }

    @Test
    void browserKeepsRequestedQuantityAndOnlyClassifiesTheRemainderOnce() {
        PlayerInventoryManager manager = manager(Map.of("minecraft:stone", 64));
        manager.observe();
        var before = manager.view(false, true, Map.of());
        manager.allocate(before.revision(), "minecraft:stone", 16);
        manager.protectUntrackedItems();
        var item = manager.view(false, true, Map.of()).items().getFirst();
        assertEquals(16, item.protectedCount());
        assertEquals(48, item.classificationCount());
        assertTrue(manager.needsRecovery());

        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        assertEquals(48, manager.claimUnexpected(task, Set.of()));
        assertEquals(Map.of("minecraft:stone", 48), task.managedItems());
        assertEquals(0, manager.claimUnexpected(task, Set.of()));
        assertThrows(IllegalStateException.class,
                () -> manager.allocate(before.revision(), "minecraft:stone", 0));
    }

    @Test
    void returningPendingClassificationItemsToPersonalUseCancelsTheirRecoveryRequest() {
        PlayerInventoryManager manager = manager(Map.of("minecraft:iron_pickaxe", 1));
        manager.observe();
        manager.allocate(manager.view(false, false, Map.of()).revision(),
                "minecraft:iron_pickaxe", 0);
        assertTrue(manager.needsRecovery());

        manager.allocate(manager.view(false, false, Map.of()).revision(),
                "minecraft:iron_pickaxe", 1);
        manager.protectUntrackedItems();

        assertFalse(manager.needsRecovery());
        assertEquals(1, manager.view(false, false, Map.of()).items().getFirst().protectedCount());
    }

    @Test
    void personalToolsCanChangeDurabilityWithoutBecomingTaskItems() {
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of("minecraft:diamond_pickaxe#new", 1));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        manager.observe();
        manager.replaceProtectedIdentity("minecraft:diamond_pickaxe#new",
                "minecraft:diamond_pickaxe#used");
        inventory.set(Map.of("minecraft:diamond_pickaxe#used", 1));
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());

        assertEquals(0, manager.claimUnexpected(task, Set.of()));
        assertEquals(Map.of("minecraft:diamond_pickaxe#used", 1),
                manager.expectedInventory().orElseThrow().itemCounts());
        assertFalse(manager.needsRecovery());
    }

    @Test
    void idleProtectionDoesNotHideMissingTaskItemsByUsingPersonalStock() {
        PlayerInventoryManager manager = manager(Map.of("minecraft:stone", 16));
        manager.observe();
        PlayerInventoryAccount task = manager.account(UUID.randomUUID());
        manager.recordWithdrawn(task, Map.of("minecraft:stone", 8));
        manager.closeAccount(task);
        manager.protectUntrackedItems();

        assertEquals(Map.of("minecraft:stone", 24),
                manager.expectedInventory().orElseThrow().itemCounts());
        assertTrue(manager.needsRecovery());
        var before = manager.view(false, true, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> manager.allocate(before.revision(), "minecraft:stone", 9));
        PlayerInventoryAccount recovery = manager.account(UUID.randomUUID());
        assertEquals(8, manager.claimUnexpected(recovery, Set.of()));
        assertEquals(Map.of("minecraft:stone", 8), recovery.managedItems());
    }

    @Test
    void newPersonalGiftsDoNotEraseAbandonedReceiptsOrManualClassificationChoices() {
        MutableInventory inventory = new MutableInventory();
        inventory.set(Map.of("minecraft:stone", 16));
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        manager.observe();
        var task = manager.account(UUID.randomUUID());
        manager.recordWithdrawn(task, Map.of("minecraft:stone", 8));
        manager.closeAccount(task);
        inventory.set(Map.of("minecraft:stone", 24, "minecraft:iron_pickaxe", 1));
        manager.observe();
        manager.protectUntrackedItems();
        var view = manager.view(false, true, Map.of());
        manager.allocate(view.revision(), "minecraft:stone", 12);
        manager.protectUntrackedItems();

        var recovery = manager.account(UUID.randomUUID());
        assertEquals(12, manager.claimUnexpected(recovery, Set.of()));
        assertEquals(Map.of("minecraft:stone", 12), recovery.managedItems());
        assertEquals(1, manager.view(false, true, Map.of()).items().stream()
                .filter(item -> item.itemId().equals("minecraft:iron_pickaxe"))
                .findFirst().orElseThrow().protectedCount());
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
            snapshot = Optional.of(new PlayerInventorySnapshot(counts));
        }

        private void unavailable() {
            snapshot = Optional.empty();
        }
    }
}
