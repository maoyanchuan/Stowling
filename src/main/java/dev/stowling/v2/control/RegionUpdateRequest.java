package dev.stowling.v2.control;

public record RegionUpdateRequest(
        String revision,
        String name,
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ
) {
}
