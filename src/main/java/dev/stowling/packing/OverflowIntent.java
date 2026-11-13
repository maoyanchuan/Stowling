package dev.stowling.packing;

import dev.stowling.domain.ContainerRef;
import java.util.Objects;

public record OverflowIntent(String itemId, int quantity, ContainerRef destination) {
    public OverflowIntent {
        itemId = PackingChecks.requireText(itemId, "itemId");
        if (quantity < 1) {
            throw new IllegalArgumentException("overflow quantity must be positive");
        }
        destination = Objects.requireNonNull(destination, "destination");
    }
}
