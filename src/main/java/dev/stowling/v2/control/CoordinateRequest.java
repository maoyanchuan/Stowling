package dev.stowling.v2.control;

public record CoordinateRequest(
        String name,
        int x,
        int y,
        int z,
        String dimension,
        Integer radius
) {
    public CoordinateRequest(String name, int x, int y, int z) {
        this(name, x, y, z, null, null);
    }
}
