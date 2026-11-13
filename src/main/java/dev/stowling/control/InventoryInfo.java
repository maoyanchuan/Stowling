package dev.stowling.control;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record InventoryInfo(
        String sourceId,
        String containerId,
        String dimension,
        Instant capturedAt,
        String freshness,
        List<InventoryItemInfo> items
) {
    public InventoryInfo {
        sourceId = requireText(sourceId, "sourceId");
        containerId = requireText(containerId, "containerId");
        dimension = requireText(dimension, "dimension");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        freshness = requireText(freshness, "freshness");
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
