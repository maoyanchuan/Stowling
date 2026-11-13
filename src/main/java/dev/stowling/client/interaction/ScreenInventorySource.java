package dev.stowling.client.interaction;

import dev.stowling.domain.ContainerRef;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventorySnapshot;
import dev.stowling.inventory.InventorySource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ScreenInventorySource implements InventorySource {
    private static final String SOURCE_ID = "opened-screen";

    private final ClientActionScheduler scheduler;
    private final MinecraftContainerInteraction containers;

    public ScreenInventorySource(
            ClientActionScheduler scheduler,
            MinecraftContainerInteraction containers
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.containers = Objects.requireNonNull(containers, "containers");
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public CompletableFuture<InventoryCapture> capture(ContainerRef container) {
        Objects.requireNonNull(container, "container");
        return scheduler.submit(() -> captureOnClientThread(container));
    }

    private InventoryCapture captureOnClientThread(ContainerRef container) {
        Optional<List<InventoryCapture.SlotCapture>> slots = containers.captureInventory(
                container
        );
        Instant capturedAt = Instant.now();
        if (slots.isEmpty()) {
            InventorySnapshot snapshot = new InventorySnapshot(
                    SOURCE_ID,
                    container.id(),
                    container.dimension(),
                    capturedAt,
                    InventoryFreshness.UNKNOWN,
                    List.of()
            );
            return new InventoryCapture(snapshot, List.of());
        }
        return InventoryCapture.fromSlots(
                SOURCE_ID,
                container.id(),
                container.dimension(),
                capturedAt,
                InventoryFreshness.FRESH,
                slots.orElseThrow()
        );
    }
}
