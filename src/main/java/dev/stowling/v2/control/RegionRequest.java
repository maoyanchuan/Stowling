package dev.stowling.v2.control;

public record RegionRequest(
        String name,
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ
) {
}
