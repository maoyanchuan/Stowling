package dev.stowling.control;

import java.util.List;
import java.util.Objects;

public record PickupWorkflowInfo(
        String id,
        String name,
        String dimension,
        List<PickupWorkflowRegionInfo> regions
) {
    public PickupWorkflowInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
    }
}
