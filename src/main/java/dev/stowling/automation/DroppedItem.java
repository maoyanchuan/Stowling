package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;
import java.util.UUID;

public record DroppedItem(
        UUID entityId,
        String itemId,
        int count,
        BlockPosition position
) {
    public DroppedItem {
        entityId = Objects.requireNonNull(entityId, "entityId");
        itemId = requireText(itemId, "itemId");
        position = Objects.requireNonNull(position, "position");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
