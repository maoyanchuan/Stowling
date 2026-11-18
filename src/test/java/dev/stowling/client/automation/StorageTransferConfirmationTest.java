package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StorageTransferConfirmationTest {
    @Test
    void refilledSupplyConfirmsOnlyOneNewBoxAndAnEmptyCursor() {
        StorageTransferConfirmation confirmation = StorageTransferConfirmation.emptyBoxSupply(2);

        assertTrue(confirmation.matches(3, true, () -> {
            throw new AssertionError("Auto-refilling supply count must not be read");
        }));
        assertFalse(confirmation.matches(2, true, () -> 54));
        assertFalse(confirmation.matches(4, true, () -> 54));
        assertFalse(confirmation.matches(3, false, () -> 54));
    }

    @Test
    void ordinaryTransfersStillRequireExactChangesOnBothSides() {
        StorageTransferConfirmation withdrawal = StorageTransferConfirmation.exact(2, 54, 1);
        StorageTransferConfirmation deposit = StorageTransferConfirmation.exact(3, 53, -1);

        assertFalse(withdrawal.matches(3, true, () -> 54));
        assertTrue(withdrawal.matches(3, true, () -> 53));
        assertFalse(deposit.matches(2, true, () -> 53));
        assertTrue(deposit.matches(2, true, () -> 54));
    }
}
