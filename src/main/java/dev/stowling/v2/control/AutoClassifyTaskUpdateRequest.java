package dev.stowling.v2.control;

import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.TaskPriority;
import java.util.List;

public record AutoClassifyTaskUpdateRequest(
        String revision,
        String name,
        Boolean enabled,
        AutoClassifyDetectionRequest detection,
        String cacheRegionRef,
        String categoryZoneRef,
        List<CategoryMarkerSource> markerSources,
        TaskPriority collectionPriority
) {
    public AutoClassifyTaskUpdateRequest {
        markerSources = markerSources == null ? List.of() : List.copyOf(markerSources);
    }
}
