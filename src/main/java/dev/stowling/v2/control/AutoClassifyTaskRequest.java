package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskCleanupPolicy;

import dev.stowling.v2.model.TaskPriority;
import java.util.List;

public record AutoClassifyTaskRequest(
        String name,
        Boolean enabled,
        Boolean collectionEnabled,
        Boolean classificationEnabled,
        AutoClassifyDetectionRequest detection,
        String cacheRegionRef,
        List<AutoClassifyAreaRequest> classificationAreas,
        TaskPriority collectionPriority,
        Boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String overflowDestinationRef
) {
    public AutoClassifyTaskRequest(
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheRegionRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority,
            Boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, null);
    }

    public AutoClassifyTaskRequest(
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheRegionRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority,
            Boolean overflowEnabled
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, overflowEnabled, null);
    }

    public AutoClassifyTaskRequest(
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheRegionRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, null);
    }

    public AutoClassifyTaskRequest {
        classificationAreas = classificationAreas == null
                ? List.of()
                : List.copyOf(classificationAreas);
    }
}
