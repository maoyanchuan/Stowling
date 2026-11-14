package dev.stowling.domain;

import java.util.Objects;

public record CuboidPickupShape(
        String firstCornerCoordinateId,
        String secondCornerCoordinateId,
        BlockPosition firstCorner,
        BlockPosition secondCorner
) implements PickupShape {
    public CuboidPickupShape {
        firstCorner = Objects.requireNonNull(firstCorner, "firstCorner");
        secondCorner = Objects.requireNonNull(secondCorner, "secondCorner");
        if (firstCorner.x() > secondCorner.x()
                || firstCorner.y() > secondCorner.y()
                || firstCorner.z() > secondCorner.z()) {
            throw new IllegalArgumentException(
                    "firstCorner coordinates must not exceed secondCorner coordinates"
            );
        }
    }

    public CuboidPickupShape(
            BlockPosition firstCorner,
            BlockPosition secondCorner
    ) {
        this(null, null, firstCorner, secondCorner);
    }

    @Override
    public PickupShapeType type() {
        return PickupShapeType.CUBOID;
    }

    @Override
    public boolean contains(double x, double y, double z) {
        return x >= firstCorner.x()
                && x <= secondCorner.x()
                && y >= firstCorner.y()
                && y <= secondCorner.y()
                && z >= firstCorner.z()
                && z <= secondCorner.z();
    }
}
