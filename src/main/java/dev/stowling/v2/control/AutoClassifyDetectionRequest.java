package dev.stowling.v2.control;

public record AutoClassifyDetectionRequest(
        String coordinateRef,
        Integer pickupRadius,
        Integer intervalSeconds
) {
    public AutoClassifyDetectionRequest(String coordinateRef, Integer pickupRadius) {
        this(coordinateRef, pickupRadius, null);
    }
}
