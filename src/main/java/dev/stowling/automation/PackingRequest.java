package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PackingRequest(
        UUID taskId,
        ContainerRef buffer,
        String routeId,
        List<String> stationIds,
        List<ItemAmount> items,
        Optional<TaskPackingPlan> taskPlan
) {
    public PackingRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(buffer, "buffer");
        routeId = requireText(routeId, "routeId");
        stationIds = List.copyOf(Objects.requireNonNull(stationIds, "stationIds"));
        if (stationIds.isEmpty()) {
            throw new IllegalArgumentException("stationIds must not be empty");
        }
        for (String stationId : stationIds) {
            requireText(stationId, "stationId");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        taskPlan = Objects.requireNonNull(taskPlan, "taskPlan");
    }

    public PackingRequest(
            UUID taskId,
            ContainerRef buffer,
            String routeId,
            List<String> stationIds,
            List<ItemAmount> items
    ) {
        this(
                taskId,
                buffer,
                routeId,
                stationIds,
                items,
                Optional.empty()
        );
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
