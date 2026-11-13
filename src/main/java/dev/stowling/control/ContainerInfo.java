package dev.stowling.control;

import java.util.Objects;

public record ContainerInfo(
        String id,
        String dimension,
        ContainerPosition position,
        String type
) {
    public ContainerInfo {
        id = requireText(id, "id");
        dimension = requireText(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
        type = requireText(type, "type");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
