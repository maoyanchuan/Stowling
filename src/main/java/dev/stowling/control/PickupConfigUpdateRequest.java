package dev.stowling.control;

import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupWorkflow;
import java.util.List;
import java.util.Objects;

public record PickupConfigUpdateRequest(
        String revision,
        List<PickupRegion> pickupRegions,
        List<NamedCache> cacheContainers,
        List<PickupWorkflow> pickupWorkflows
) {
    public PickupConfigUpdateRequest {
        revision = Objects.requireNonNull(revision, "revision");
        if (revision.isBlank()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
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
