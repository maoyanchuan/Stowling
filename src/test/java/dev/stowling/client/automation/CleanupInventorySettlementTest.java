package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.runtime.modules.PlayerInventoryManager;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CleanupInventorySettlementTest {
    private static final String STONE = "minecraft:stone";
    private static final String TOOL = "minecraft:diamond_pickaxe";

    @Test
    void continuouslyRefilledFullInventoryDoesNotGrowTheCleanupBatch() {
        var actual = new AtomicReference<>(new PlayerInventorySnapshot(Map.of()));
        var inventory = new PlayerInventoryManager(() -> Optional.of(actual.get()));
        inventory.observe();
        UUID collectionTask = UUID.randomUUID();
        inventory.retainCollectionTasks(Set.of(collectionTask));
        var account = inventory.account(UUID.randomUUID());
        account.recordWithdrawn(STONE, 1728);
        actual.set(new PlayerInventorySnapshot(Map.of(STONE, 2176)));
        inventory.recordPickup(collectionTask, STONE, 448);
        var settlement = new CleanupInventorySettlement(inventory, account);
        for (int tick = 0; tick < 5; tick++) settlement.tick();
        assertEquals(Map.of(STONE, 1728), account.managedItems());

        for (int batch = 1; batch <= 6; batch++) {
            account.confirmDeposit("managed", Map.of(STONE, 288), Map.of());
            inventory.recordPickup(collectionTask, STONE, 288);
            inventory.observe();
            assertEquals(TaskModuleState.SUCCEEDED, settlement.tick().state());
            assertEquals(1728 - batch * 288, account.managedItems().getOrDefault(STONE, 0));
        }

        assertTrue(account.managedItems().isEmpty());
        assertTrue(inventory.hasCollectedInput(collectionTask));
        inventory.closeAccount(account);
        var next = inventory.account(UUID.randomUUID());
        next.usePickupReceipts(collectionTask);
        assertEquals(2176, inventory.claimCollectionItems(next, Set.of()));
        assertEquals(Map.of(STONE, 2176), next.managedItems());
        assertFalse(inventory.hasAbandonedAccounts());
        assertEquals(actual.get(), inventory.expectedInventory().orElseThrow());
    }

    @Test
    void changingInventoryStartsAFiniteBatchAfterTwentyTicks() {
        var actual = new AtomicReference<>(new PlayerInventorySnapshot(Map.of()));
        var inventory = new PlayerInventoryManager(() -> Optional.of(actual.get()));
        inventory.observe();
        var account = inventory.account(UUID.randomUUID());
        var settlement = new CleanupInventorySettlement(inventory, account);

        for (int tick = 1; tick <= 20; tick++) {
            actual.set(new PlayerInventorySnapshot(Map.of(STONE, tick * 64)));
            var result = tick == 1 ? settlement.start() : settlement.tick();
            assertEquals(tick < 20 ? TaskModuleState.WAITING : TaskModuleState.SUCCEEDED,
                    result.state());
        }

        assertEquals(Map.of(STONE, 1280), account.managedItems());
        actual.set(new PlayerInventorySnapshot(Map.of(STONE, 1344)));
        inventory.observe();
        assertEquals(TaskModuleState.SUCCEEDED, settlement.tick().state());
        assertEquals(Map.of(STONE, 1280), account.managedItems());
        var next = inventory.account(UUID.randomUUID());
        assertEquals(64, inventory.claimUnexpected(next, Set.of()));
    }

    @Test
    void stableInventorySettlesEarlyAndReservesProtectedAndOtherTaskStock() {
        var actual = new AtomicReference<>(new PlayerInventorySnapshot(Map.of(TOOL, 1)));
        var inventory = new PlayerInventoryManager(() -> Optional.of(actual.get()));
        inventory.observe();
        inventory.allocate(inventory.view(false, true, Map.of()).revision(), TOOL, 1);
        var account = inventory.account(UUID.randomUUID());
        account.recordWithdrawn(STONE, 128);
        var other = inventory.account(UUID.randomUUID());
        other.recordWithdrawn(STONE, 32);
        actual.set(new PlayerInventorySnapshot(Map.of(TOOL, 1, STONE, 96)));
        var settlement = new CleanupInventorySettlement(inventory, account);

        for (int tick = 0; tick < 4; tick++) {
            assertEquals(TaskModuleState.WAITING, settlement.tick().state());
        }
        assertEquals(TaskModuleState.SUCCEEDED, settlement.tick().state());
        assertEquals(Map.of(STONE, 64), account.managedItems());
        assertEquals(Map.of(STONE, 32), other.managedItems());
        assertEquals(actual.get(), inventory.expectedInventory().orElseThrow());
        assertEquals(1, inventory.view(false, true, Map.of()).items().stream()
                .filter(item -> TOOL.equals(item.itemId())).findFirst().orElseThrow()
                .protectedCount());
    }

    @Test
    void unavailableInventoryFailsWithoutDiscardingTaskCargo() {
        var inventory = new PlayerInventoryManager(Optional::empty);
        var account = inventory.account(UUID.randomUUID());
        account.recordWithdrawn(STONE, 64);
        var settlement = new CleanupInventorySettlement(inventory, account);

        assertEquals(TaskModuleState.FAILED, settlement.start().state());
        assertEquals(Map.of(STONE, 64), account.managedItems());
    }
}
