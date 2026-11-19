package dev.stowling.client.automation;

import java.util.OptionalInt;
import java.util.function.IntSupplier;

record StorageTransferConfirmation(int expectedPlayer, OptionalInt expectedContainer) {
    static StorageTransferConfirmation exact(int playerBefore, int containerBefore,
            int playerDelta) {
        return new StorageTransferConfirmation(playerBefore + playerDelta,
                OptionalInt.of(containerBefore - playerDelta));
    }

    static StorageTransferConfirmation emptyBoxSupply(int playerBefore) {
        return new StorageTransferConfirmation(playerBefore + 1, OptionalInt.empty());
    }

    boolean matches(int playerAfter, boolean cursorEmpty, IntSupplier containerAfter) {
        return matches(playerAfter, 0, cursorEmpty, containerAfter);
    }

    boolean matches(int playerAfter, long pickedUp, boolean cursorEmpty,
            IntSupplier containerAfter) {
        return pickedUp >= 0 && (long) playerAfter - pickedUp == expectedPlayer && cursorEmpty
                && (expectedContainer.isEmpty()
                || containerAfter.getAsInt() == expectedContainer.getAsInt());
    }
}
