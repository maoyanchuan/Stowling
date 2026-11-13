package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PackingRequest(
        UUID taskId,
        ContainerRef buffer,
        String routeId,
        String stationId,
        List<ItemAmount> items
) {
    public PackingRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(buffer, "buffer");
        routeId = requireText(routeId, "routeId");
        stationId = requireText(stationId, "stationId");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
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
