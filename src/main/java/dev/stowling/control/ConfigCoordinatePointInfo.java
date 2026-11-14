package dev.stowling.control;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.NamedCoordinate;
import java.util.Objects;

public record ConfigCoordinatePointInfo(
        String id,
        String name,
        String dimension,
        ContainerPosition position,
        CoordinateType type,
        Integer radius,
        ContainerPosition secondCorner
) {
    public ConfigCoordinatePointInfo {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        dimension = Objects.requireNonNull(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
        type = type == null ? CoordinateType.POINT : type;
        new NamedCoordinate(
                id, name, dimension,
                new BlockPosition(position.x(), position.y(), position.z()),
                type, radius,
                secondCorner == null ? null : new BlockPosition(
                        secondCorner.x(), secondCorner.y(), secondCorner.z()
                )
        );
    }

    public ConfigCoordinatePointInfo(
            String id,
            String name,
            String dimension,
            ContainerPosition position
    ) {
        this(id, name, dimension, position, CoordinateType.POINT, null, null);
    }
}
