package dev.stowling.v2.control;

import dev.stowling.v2.category.CategoryMarkerSource;
import java.util.List;

public record AutoClassifyAreaRequest(
        String categoryZoneRef,
        String ruleId,
        List<CategoryMarkerSource> markerSources,
        dev.stowling.v2.model.StoragePolicy storagePolicy
) {
    public AutoClassifyAreaRequest(String categoryZoneRef, String ruleId,
            List<CategoryMarkerSource> markerSources) {
        this(categoryZoneRef, ruleId, markerSources, null);
    }

    public AutoClassifyAreaRequest {
        markerSources = markerSources == null
                ? List.of()
                : List.copyOf(markerSources);
    }
}
