package dev.stowling.v2.control;

public record CategoryZoneExpansionRequest(
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ,
        String direction,
        int step
) {
}
