package dev.stowling.v2.control;

public record CoordinateUpdateRequest(
        String revision,
        String name,
        int x,
        int y,
        int z,
        String dimension,
        Integer radius
) {
    public CoordinateUpdateRequest(
            String revision,
            String name,
            int x,
            int y,
            int z
    ) {
        this(revision, name, x, y, z, null, null);
    }
}
