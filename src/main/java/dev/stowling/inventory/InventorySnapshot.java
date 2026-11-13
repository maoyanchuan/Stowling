package dev.stowling.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record InventorySnapshot(
        String sourceId,
        String containerId,
        String dimension,
        Instant capturedAt,
        InventoryFreshness freshness,
        List<InventoryItem> items
) {
    public InventorySnapshot {
        sourceId = requireText(sourceId, "sourceId");
        containerId = requireText(containerId, "containerId");
        dimension = requireText(dimension, "dimension");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        freshness = Objects.requireNonNull(freshness, "freshness");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
