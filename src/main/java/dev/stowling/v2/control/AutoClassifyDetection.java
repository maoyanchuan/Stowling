package dev.stowling.v2.control;

public record AutoClassifyDetection(
        String coordinateRef,
        Integer pickupRadius,
        Integer intervalSeconds
) {
    public AutoClassifyDetection(String coordinateRef, Integer pickupRadius) {
        this(coordinateRef, pickupRadius, null);
    }
}
