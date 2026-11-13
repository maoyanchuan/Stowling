package dev.stowling.inventory;

import java.util.Objects;

public record InventoryItem(String itemId, int count, InventoryItemKind kind) {
    public InventoryItem {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        Objects.requireNonNull(kind, "kind");
    }
}
