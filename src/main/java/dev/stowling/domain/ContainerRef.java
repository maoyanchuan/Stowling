package dev.stowling.domain;

import java.util.Objects;

public record ContainerRef(
        String id,
        String dimension,
        BlockPosition position,
        ContainerType type
) {
    public ContainerRef {
        id = requireText(id, "container id");
        dimension = requireText(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
        type = Objects.requireNonNull(type, "type");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
