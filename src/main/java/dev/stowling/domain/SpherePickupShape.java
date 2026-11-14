package dev.stowling.domain;

import java.util.Objects;

public record SpherePickupShape(BlockPosition center, int radius) implements PickupShape {
    public SpherePickupShape {
        center = Objects.requireNonNull(center, "center");
        if (radius < 1 || radius > 128) {
            throw new IllegalArgumentException("radius must be between 1 and 128");
        }
    }

    @Override
    public PickupShapeType type() {
        return PickupShapeType.SPHERE;
    }

    @Override
    public boolean contains(double x, double y, double z) {
        double deltaX = x - center.x();
        double deltaY = y - center.y();
        double deltaZ = z - center.z();
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        return distanceSquared <= (double) radius * radius;
    }
}
