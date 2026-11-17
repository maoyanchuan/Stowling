package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;

public record AreaBounds(
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ
) {
    public int minX() {
        return Math.min(firstX, secondX);
    }

    public int minY() {
        return Math.min(firstY, secondY);
    }

    public int minZ() {
        return Math.min(firstZ, secondZ);
    }

    public int maxX() {
        return Math.max(firstX, secondX);
    }

    public int maxY() {
        return Math.max(firstY, secondY);
    }

    public int maxZ() {
        return Math.max(firstZ, secondZ);
    }

    public boolean contains(AreaBounds other) {
        return other.minX() >= minX()
                && other.maxX() <= maxX()
                && other.minY() >= minY()
                && other.maxY() <= maxY()
                && other.minZ() >= minZ()
                && other.maxZ() <= maxZ();
    }

    public boolean contains(BlockPosition position) {
        return position.x() >= minX() && position.x() <= maxX()
                && position.y() >= minY() && position.y() <= maxY()
                && position.z() >= minZ() && position.z() <= maxZ();
    }

    public AreaBounds shift(int x, int y, int z) {
        return new AreaBounds(
                shifted(firstX, x),
                shifted(firstY, y),
                shifted(firstZ, z),
                shifted(secondX, x),
                shifted(secondY, y),
                shifted(secondZ, z)
        );
    }

    private static int shifted(int value, int delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "自动铺满后的坐标超出范围",
                    exception
            );
        }
    }
}
