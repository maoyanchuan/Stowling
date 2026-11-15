package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;

public record ContainerTarget(
        BlockPosition position,
        String type
) {
    public ContainerTarget {
        position = Objects.requireNonNull(position, "position");
        type = requireText(type, "type");
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
