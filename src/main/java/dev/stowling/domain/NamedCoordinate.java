package dev.stowling.domain;

import java.util.Objects;

public record NamedCoordinate(
        String id,
        String name,
        String dimension,
        BlockPosition position,
        CoordinateType type,
        Integer radius,
        BlockPosition secondCorner
) {
    public NamedCoordinate {
        id = DomainChecks.requireText(id, "coordinate id");
        name = DomainChecks.requireText(name, "coordinate name");
        dimension = DomainChecks.requireText(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
        type = type == null ? CoordinateType.POINT : type;
        switch (type) {
            case POINT -> {
                requireAbsent(radius, "radius", type);
                requireAbsent(secondCorner, "secondCorner", type);
            }
            case SPHERE, CUBE -> {
                if (radius == null || radius < 1 || radius > 128) {
                    throw new IllegalArgumentException(
                            "radius must be between 1 and 128 for " + type
                    );
                }
                requireAbsent(secondCorner, "secondCorner", type);
            }
            case AREA -> {
                requireAbsent(radius, "radius", type);
                if (secondCorner == null) {
                    throw new IllegalArgumentException("secondCorner is required for AREA");
                }
            }
            default -> throw new IllegalStateException("unsupported coordinate type");
        }
    }

    public NamedCoordinate(
            String id,
            String name,
            String dimension,
            BlockPosition position
    ) {
        this(id, name, dimension, position, CoordinateType.POINT, null, null);
    }

    public PickupShape pickupShape() {
        return switch (type) {
            case POINT -> throw new IllegalArgumentException(
                    "coordinate " + id + " is a point and has no pickup range"
            );
            case SPHERE -> new SpherePickupShape(id, position, radius);
            case CUBE -> cubeShape();
            case AREA -> areaShape();
            default -> throw new IllegalStateException("unsupported coordinate type");
        };
    }

    public BlockPosition anchorPosition() {
        if (type != CoordinateType.AREA) {
            return position;
        }
        return new BlockPosition(
                midpoint(position.x(), secondCorner.x()),
                midpoint(position.y(), secondCorner.y()),
                midpoint(position.z(), secondCorner.z())
        );
    }

    private CuboidPickupShape cubeShape() {
        int extent = radius;
        BlockPosition minimum = new BlockPosition(
                position.x() - extent,
                position.y() - extent,
                position.z() - extent
        );
        BlockPosition maximum = new BlockPosition(
                position.x() + extent,
                position.y() + extent,
                position.z() + extent
        );
        return new CuboidPickupShape(id, null, minimum, maximum);
    }

    private CuboidPickupShape areaShape() {
        BlockPosition minimum = new BlockPosition(
                Math.min(position.x(), secondCorner.x()),
                Math.min(position.y(), secondCorner.y()),
                Math.min(position.z(), secondCorner.z())
        );
        BlockPosition maximum = new BlockPosition(
                Math.max(position.x(), secondCorner.x()),
                Math.max(position.y(), secondCorner.y()),
                Math.max(position.z(), secondCorner.z())
        );
        return new CuboidPickupShape(id, null, minimum, maximum);
    }

    private static int midpoint(int first, int second) {
        return (int) (((long) first + second) / 2L);
    }

    private static void requireAbsent(
            Object value,
            String field,
            CoordinateType coordinateType
    ) {
        if (value != null) {
            throw new IllegalArgumentException(
                    field + " is not valid for " + coordinateType
            );
        }
    }
}
