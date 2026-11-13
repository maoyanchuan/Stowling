package dev.stowling.domain;

import java.util.Objects;

public record PackingStation(
        String id,
        String dimension,
        BlockPosition shulkerBoxPosition,
        ContainerRef productContainer,
        PackingTrigger completionTrigger,
        int completionThreshold
) {
    public PackingStation {
        id = DomainChecks.requireText(id, "packing station id");
        dimension = DomainChecks.requireText(dimension, "dimension");
        shulkerBoxPosition = Objects.requireNonNull(
                shulkerBoxPosition,
                "shulkerBoxPosition"
        );
        productContainer = Objects.requireNonNull(productContainer, "productContainer");
        completionTrigger = Objects.requireNonNull(completionTrigger, "completionTrigger");
        if (!dimension.equals(productContainer.dimension())) {
            throw new IllegalArgumentException("product container dimension must match station");
        }
        if (completionTrigger == PackingTrigger.ON_OCCUPANCY
                && (completionThreshold < 1 || completionThreshold > 100)) {
            throw new IllegalArgumentException("occupancy threshold must be between 1 and 100");
        }
        if (completionTrigger == PackingTrigger.ON_ITEM_COUNT
                && (completionThreshold < 1 || completionThreshold > 1728)) {
            throw new IllegalArgumentException("item threshold must be between 1 and 1728");
        }
    }
}
