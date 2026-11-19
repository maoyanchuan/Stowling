package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.runtime.modules.PlayerInventoryAccount;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerRecoveryReceiptTest {
    @Test
    void retryKeepsThePreMiningBaselineEvenIfTheBoxWasAlreadyPickedUp() {
        var receipt = new ContainerRecoveryReceipt();
        receipt.begin(Map.of("old-box", 1), Set.of("old-box"), Set.of());
        Map<String, Integer> after = Map.of("old-box", 1, "server-box", 1);
        // Cleanup replaces the action; it must not establish a new baseline from this snapshot.
        receipt.begin(after, Set.of("old-box", "server-box"), Set.of());

        assertEquals("server-box", receipt.acquiredKey(after,
                Set.of("old-box", "server-box")).orElseThrow());
        var account = new PlayerInventoryAccount(UUID.randomUUID());
        receipt.confirm(() -> account.recordWithdrawn("server-box", 1));
        assertTrue(receipt.acquiredKey(after, Set.of("old-box", "server-box")).isEmpty());
        assertEquals(Map.of("server-box", 1), account.managedItems());
    }

    @Test
    void aPreexistingEquivalentBoxOrAChangedKeyAloneDoesNotCountAsRecovery() {
        var receipt = new ContainerRecoveryReceipt();
        receipt.begin(Map.of("old-encoding", 1), Set.of("old-encoding"), Set.of());

        assertTrue(receipt.acquiredKey(Map.of("old-encoding", 1),
                Set.of("old-encoding")).isEmpty());
        assertTrue(receipt.acquiredKey(Map.of("server-encoding", 1),
                Set.of("server-encoding")).isEmpty());
        assertTrue(receipt.acquiredKey(Map.of("unrelated-box", 1), Set.of()).isEmpty());
    }

    @Test
    void freeingAReservedSlotAdjustsOnlyTheConfirmedRemovedBaselineQuantity() {
        var receipt = new ContainerRecoveryReceipt();
        receipt.begin(Map.of("matching-box", 1, "stone", 64), Set.of("matching-box"), Set.of());
        receipt.recordRemoval("stone", 64);
        assertTrue(receipt.acquiredKey(Map.of("matching-box", 1),
                Set.of("matching-box")).isEmpty());
        receipt.recordRemoval("matching-box", 1);

        assertEquals("matching-box", receipt.acquiredKey(Map.of("matching-box", 1),
                Set.of("matching-box")).orElseThrow());
    }

    @Test
    void onlyTheNewTrackedDropIsSuppressedWhileExistingDropsRemainNormalPickups() {
        var receipt = new ContainerRecoveryReceipt();
        UUID existing = UUID.randomUUID();
        UUID registered = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertFalse(receipt.acceptsDrop(registered));
        receipt.begin(Map.of(), Set.of(), Set.of(existing));
        assertFalse(receipt.acceptsDrop(existing));
        assertTrue(receipt.acceptsDrop(registered));
        receipt.trackDrop(registered);
        assertTrue(receipt.tracksDrop(registered));
        assertTrue(receipt.acceptsDrop(registered));
        assertFalse(receipt.acceptsDrop(other));
    }

    @Test
    void cleanupAfterAnInterruptedCompletionDoesNotBookTheSameBoxTwice() {
        String box = "minecraft:shulker_box#recovered@minecraft:stone";
        var account = new PlayerInventoryAccount(UUID.randomUUID());
        var receipt = new ContainerRecoveryReceipt();
        assertFalse(receipt.confirmed());
        receipt.confirm(() -> account.recordWithdrawn(box, 1));
        // The old action finished, but interruption skipped its remove-resource callback.
        receipt.confirm(() -> account.recordWithdrawn(box, 1));
        assertTrue(receipt.confirmed());
        assertEquals(Map.of(box, 1), account.managedItems());
        account.confirmDeposit("managed", Map.of(box, 1), Map.of());
        assertTrue(account.managedItems().isEmpty());
    }

    @Test
    void failedBookkeepingRemainsRetryable() {
        var receipt = new ContainerRecoveryReceipt();
        assertThrows(IllegalStateException.class, () -> receipt.confirm(() -> {
            throw new IllegalStateException("not recorded");
        }));
        assertFalse(receipt.confirmed());
        receipt.confirm(() -> { });
        assertTrue(receipt.confirmed());
    }
}
