package dev.stowling.client.automation;

import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.PlayerInventoryAdapter;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.util.Objects;

/**
 * Reads the authoritative live player inventory only on the client tick thread.
 */
public final class ClientThreadInventoryQueryPort implements InventoryQueryPort {
    private final PlayerInventoryAdapter playerInventory;
    private final ClientActionScheduler scheduler;

    public ClientThreadInventoryQueryPort(
            PlayerInventoryAdapter playerInventory,
            ClientActionScheduler scheduler
    ) {
        this.playerInventory = Objects.requireNonNull(
                playerInventory,
                "playerInventory"
        );
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public PlayerInventorySnapshot snapshot() {
        if (!scheduler.isClientThread()) {
            throw new IllegalStateException(
                    "player inventory query must run on the Minecraft client thread"
            );
        }
        return Objects.requireNonNull(
                playerInventory.observe(),
                "player inventory snapshot"
        );
    }
}
