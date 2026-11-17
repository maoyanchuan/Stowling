package dev.stowling.v2.control;

import dev.stowling.v2.category.CategoryMarkerSource;
import java.util.List;

public record AutoClassifyAreaRequest(
        String categoryZoneRef,
        String ruleId,
        List<CategoryMarkerSource> markerSources
) {
    public AutoClassifyAreaRequest {
        markerSources = markerSources == null
                ? List.of()
                : List.copyOf(markerSources);
    }
}
