package dev.stowling.domain;

import java.util.Objects;

public record PickupRegion(
        String id,
        String name,
        String dimension,
        String standbyCoordinateId,
        BlockPosition standbyPosition,
        PickupShape shape
) {
    public PickupRegion {
        id = DomainChecks.requireText(id, "pickup region id");
        name = DomainChecks.requireText(name, "pickup region name");
        dimension = DomainChecks.requireText(dimension, "dimension");
        standbyPosition = Objects.requireNonNull(standbyPosition, "standbyPosition");
        shape = Objects.requireNonNull(shape, "shape");
    }

    public PickupRegion(
            String id,
            String name,
            String dimension,
            BlockPosition standbyPosition,
            PickupShape shape
    ) {
        this(id, name, dimension, null, standbyPosition, shape);
    }

    public boolean contains(BlockPosition position) {
        return shape.contains(position);
    }

    public boolean contains(double x, double y, double z) {
        return shape.contains(x, y, z);
    }
}
