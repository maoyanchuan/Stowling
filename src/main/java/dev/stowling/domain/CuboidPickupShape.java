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

    public static CuboidPickupShape fromCoordinates(
            NamedCoordinate first,
            NamedCoordinate second
    ) {
        BlockPosition a = first.position();
        BlockPosition b = second.position();
        return new CuboidPickupShape(
                first.id(),
                second.id(),
                new BlockPosition(
                        Math.min(a.x(), b.x()),
                        Math.min(a.y(), b.y()),
                        Math.min(a.z(), b.z())
                ),
                new BlockPosition(
                        Math.max(a.x(), b.x()),
                        Math.max(a.y(), b.y()),
                        Math.max(a.z(), b.z())
                )
        );
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
