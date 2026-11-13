package dev.stowling.packing;

import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.StorageRoute;
import java.util.Objects;

public record PackingRequest(
        String taskId,
        String dimension,
        String itemId,
        int quantity,
        StorageRoute route,
        ContainerRef overflowContainer
) {
    public PackingRequest {
        taskId = PackingChecks.requireText(taskId, "taskId");
        dimension = PackingChecks.requireText(dimension, "dimension");
        itemId = PackingChecks.requireText(itemId, "itemId");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        route = Objects.requireNonNull(route, "route");
        overflowContainer = Objects.requireNonNull(overflowContainer, "overflowContainer");
        if (!dimension.equals(route.bulkContainer().dimension())) {
            throw new IllegalArgumentException(
                    "route bulk container must be in the packing dimension"
            );
        }
        if (!dimension.equals(overflowContainer.dimension())) {
            throw new IllegalArgumentException(
                    "overflow container must be in the packing dimension"
            );
        }
    }
}
