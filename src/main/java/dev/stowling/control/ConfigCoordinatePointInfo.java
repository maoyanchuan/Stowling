package dev.stowling.control;

import java.util.Objects;

public record ConfigCoordinatePointInfo(
        String id,
        String name,
        String dimension,
        ContainerPosition position
) {
    public ConfigCoordinatePointInfo {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        dimension = Objects.requireNonNull(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
    }
}
