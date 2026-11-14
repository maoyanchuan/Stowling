package dev.stowling.client.interaction;

import dev.stowling.domain.ContainerRef;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventorySource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ScreenInventorySource implements InventorySource {
    private static final String SOURCE_ID = "opened-screen";

    private final ClientActionScheduler scheduler;
    private final CaptureReader captureInventory;

    public ScreenInventorySource(
            ClientActionScheduler scheduler,
            MinecraftContainerInteraction containers
    ) {
        this(
                scheduler,
                Objects.requireNonNull(containers, "containers")::captureInventory
        );
    }

    ScreenInventorySource(
            ClientActionScheduler scheduler,
            CaptureReader captureInventory
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.captureInventory = Objects.requireNonNull(
                captureInventory,
                "captureInventory"
        );
    }

    @FunctionalInterface
    interface CaptureReader {
        Optional<List<InventoryCapture.SlotCapture>> capture(
                ContainerRef container
        );
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
        List<InventoryCapture.SlotCapture> slots = Objects.requireNonNull(
                captureInventory.capture(container),
                "capture result"
        )
                .orElseThrow(() -> new IllegalStateException(
                        "container is not open in the current screen"
                ));
        Instant capturedAt = Instant.now();
        return InventoryCapture.fromSlots(
                SOURCE_ID,
                container.id(),
                container.dimension(),
                capturedAt,
                InventoryFreshness.FRESH,
                slots
        );
    }
}
