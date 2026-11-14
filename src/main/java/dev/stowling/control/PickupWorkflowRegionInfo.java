package dev.stowling.control;

public record PickupWorkflowRegionInfo(
        String id,
        String name,
        int priority,
        long idleDelay
) {
}
