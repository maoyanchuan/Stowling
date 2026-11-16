package dev.stowling.v2.control;

public record AutoClassifyDetectionRequest(
        String coordinateRef,
        Integer pickupRadius
) {
}
