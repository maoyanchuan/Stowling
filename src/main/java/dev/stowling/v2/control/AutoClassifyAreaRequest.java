package dev.stowling.v2.control;

import dev.stowling.v2.category.CategoryMarkerSource;
import java.util.List;
import java.util.UUID;

public record AutoClassifyAreaRequest(
        String categoryZoneRef,
        UUID bindingId,
        String ruleId,
        List<CategoryMarkerSource> markerSources
) {
    public AutoClassifyAreaRequest {
        markerSources = markerSources == null
                ? List.of()
                : List.copyOf(markerSources);
    }
}
