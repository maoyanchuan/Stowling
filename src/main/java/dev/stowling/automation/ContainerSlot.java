package dev.stowling.automation;

import java.util.Objects;

public record ContainerSlot(int slot, String itemId, int count) {
    public ContainerSlot {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must not be negative");
        }
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
