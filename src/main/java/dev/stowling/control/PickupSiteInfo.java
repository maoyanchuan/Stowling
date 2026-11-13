package dev.stowling.control;

public record PickupSiteInfo(
        String id,
        String dimension,
        ContainerPosition center,
        int radius,
        long idleDelaySeconds
) {
}
