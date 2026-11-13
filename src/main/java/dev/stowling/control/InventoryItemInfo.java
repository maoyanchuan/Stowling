package dev.stowling.control;

import java.util.Objects;

public record InventoryItemInfo(String itemId, int count, String kind) {
    public InventoryItemInfo {
        itemId = requireText(itemId, "itemId");
        kind = requireText(kind, "kind");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
