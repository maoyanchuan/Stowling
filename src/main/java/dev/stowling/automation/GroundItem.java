package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;

public record GroundItem(
        String observationKey,
        String itemId,
        int count,
        BlockPosition position
) {
    public GroundItem {
        observationKey = requireText(observationKey, "observationKey");
        itemId = requireText(itemId, "itemId");
        position = Objects.requireNonNull(position, "position");
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
