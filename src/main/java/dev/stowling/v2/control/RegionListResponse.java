package dev.stowling.v2.control;

import dev.stowling.v2.model.RegionDefinition;
import java.util.List;

public record RegionListResponse(
        String revision,
        List<RegionDefinition> regions
) {
    public RegionListResponse {
        regions = List.copyOf(regions);
    }
}
