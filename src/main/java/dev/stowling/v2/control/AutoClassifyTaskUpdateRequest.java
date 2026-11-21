package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskCleanupPolicy;

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
        TaskPriority collectionPriority,
        Boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String overflowDestinationRef,
        dev.stowling.v2.model.CacheIntakePolicy cachePolicy
) {
    public AutoClassifyTaskUpdateRequest(String revision, String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheRegionRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, null);
    }

    public AutoClassifyTaskUpdateRequest(
            String revision,
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
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, null);
    }

    public AutoClassifyTaskUpdateRequest(
            String revision,
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
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, overflowEnabled, null);
    }

    public AutoClassifyTaskUpdateRequest(
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
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, null);
    }

    public AutoClassifyTaskUpdateRequest {
        classificationAreas = classificationAreas == null
                ? List.of()
                : List.copyOf(classificationAreas);
    }
}
