package dev.stowling.v2.model;

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
}
