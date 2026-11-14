package dev.stowling.domain;

public sealed interface PickupShape permits CuboidPickupShape, SpherePickupShape {
    PickupShapeType type();

    boolean contains(double x, double y, double z);

    default boolean contains(BlockPosition position) {
        return contains(position.x(), position.y(), position.z());
    }
}
