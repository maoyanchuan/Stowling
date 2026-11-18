package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.runtime.modules.PlayerInventoryAccount;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerRecoveryReceiptTest {
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
