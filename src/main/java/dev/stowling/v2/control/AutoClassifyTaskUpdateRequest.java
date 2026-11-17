package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskPriority;
import java.util.List;

public record AutoClassifyTaskUpdateRequest(
        String revision,
        String name,
        Boolean enabled,
        Boolean collectionEnabled,
        Boolean classificationEnabled,
        AutoClassifyDetectionRequest detection,
        String cacheRegionRef,
        List<AutoClassifyAreaRequest> classificationAreas,
        TaskPriority collectionPriority
) {
    public AutoClassifyTaskUpdateRequest {
        classificationAreas = classificationAreas == null
                ? List.of()
                : List.copyOf(classificationAreas);
    }
}
