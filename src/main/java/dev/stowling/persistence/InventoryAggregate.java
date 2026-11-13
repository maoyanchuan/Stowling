package dev.stowling.persistence;

import dev.stowling.inventory.InventoryItemKind;
import java.util.Objects;

public record InventoryAggregate(
        String itemId,
        InventoryItemKind kind,
        long totalCount,
        long freshCount,
        long staleCount,
        long unknownCount
) {
    public InventoryAggregate {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        kind = Objects.requireNonNull(kind, "kind");
        if (totalCount < 0 || freshCount < 0 || staleCount < 0 || unknownCount < 0) {
            throw new IllegalArgumentException("inventory counts must not be negative");
        }
        if (freshCount + staleCount + unknownCount != totalCount) {
            throw new IllegalArgumentException("freshness counts must sum to totalCount");
        }
    }
}
