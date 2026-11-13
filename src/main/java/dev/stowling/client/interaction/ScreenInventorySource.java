package dev.stowling.client.interaction;

import dev.stowling.automation.ContainerSlot;
import dev.stowling.automation.ContainerView;
import dev.stowling.domain.ContainerRef;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import dev.stowling.inventory.InventorySource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    public CompletableFuture<InventorySnapshot> capture(ContainerRef container) {
        Objects.requireNonNull(container, "container");
        return scheduler.submit(() -> captureOnClientThread(container));
    }

    private InventorySnapshot captureOnClientThread(ContainerRef container) {
        Optional<ContainerView> view = containers.observe(container);
        if (view.isEmpty()) {
            return new InventorySnapshot(
                    SOURCE_ID,
                    container.id(),
                    container.dimension(),
                    Instant.now(),
                    InventoryFreshness.UNKNOWN,
                    List.of()
            );
        }
        List<InventoryItem> items = new ArrayList<>();
        for (ContainerSlot slot : view.orElseThrow().slots()) {
            items.add(new InventoryItem(
                    slot.itemId(),
                    slot.count(),
                    kindOf(slot.itemId())
            ));
        }
        return new InventorySnapshot(
                SOURCE_ID,
                container.id(),
                container.dimension(),
                Instant.now(),
                InventoryFreshness.FRESH,
                items
        );
    }

    private static InventoryItemKind kindOf(String itemId) {
        String normalized = itemId.toLowerCase(Locale.ROOT);
        return normalized.endsWith("shulker_box")
                ? InventoryItemKind.BOXED
                : InventoryItemKind.LOOSE;
    }
}
