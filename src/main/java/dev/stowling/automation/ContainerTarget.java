package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.Objects;

public record ContainerTarget(
        BlockPosition position,
        String type,
        AreaBounds standingArea,
        dev.stowling.v2.model.StoragePolicy storagePolicy
) {
    public ContainerTarget {
        storagePolicy = storagePolicy == null
                ? dev.stowling.v2.model.StoragePolicy.DEFAULT : storagePolicy;
        position = Objects.requireNonNull(position, "position");
        type = requireText(type, "type");
    }

    public ContainerTarget(BlockPosition position, String type, AreaBounds standingArea) {
        this(position, type, standingArea, null);
    }

    public ContainerTarget(BlockPosition position, String type) {
        this(position, type, null);
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
