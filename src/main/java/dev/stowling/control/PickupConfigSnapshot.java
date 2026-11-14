package dev.stowling.control;

import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupWorkflow;
import java.util.List;
import java.util.Objects;

public record PickupConfigSnapshot(
        String revision,
        List<PickupRegion> pickupRegions,
        List<NamedCache> cacheContainers,
        List<PickupWorkflow> pickupWorkflows
) {
    public PickupConfigSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        pickupRegions = List.copyOf(Objects.requireNonNull(
                pickupRegions,
                "pickupRegions"
        ));
        cacheContainers = List.copyOf(Objects.requireNonNull(
                cacheContainers,
                "cacheContainers"
        ));
        pickupWorkflows = List.copyOf(Objects.requireNonNull(
                pickupWorkflows,
                "pickupWorkflows"
        ));
    }
}
