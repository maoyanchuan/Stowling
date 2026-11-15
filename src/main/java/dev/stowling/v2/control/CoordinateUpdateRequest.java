package dev.stowling.v2.control;

public record CoordinateUpdateRequest(
        String revision,
        String name,
        int x,
        int y,
        int z
) {
}
