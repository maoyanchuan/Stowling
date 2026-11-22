package dev.stowling.v2.control;

import dev.stowling.v2.model.AutoClassifyDefinition.Kind;
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
        String cacheLocationRef,
        List<AutoClassifyAreaRequest> classificationAreas,
        TaskPriority collectionPriority,
        Boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String overflowDestinationRef,
        dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
        Kind kind,
        String followUpTaskRef,
        Integer followUpDelaySeconds
) {
    public AutoClassifyTaskUpdateRequest(String revision, String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef, dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind, String followUpTaskRef) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, kind, followUpTaskRef, null);
    }

    public AutoClassifyTaskUpdateRequest(String revision, String name,
            Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef, dev.stowling.v2.model.CacheIntakePolicy cachePolicy) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, null, null);
    }

    public AutoClassifyTaskUpdateRequest(String revision, String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, null);
    }

    public AutoClassifyTaskUpdateRequest(
            String revision,
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority,
            Boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy
    ) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, null);
    }

    public AutoClassifyTaskUpdateRequest(
            String revision,
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority,
            Boolean overflowEnabled
    ) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled, null);
    }

    public AutoClassifyTaskUpdateRequest(
            String revision,
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority
    ) {
        this(revision, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, null);
    }

    public AutoClassifyTaskUpdateRequest {
        classificationAreas = classificationAreas == null
                ? List.of()
                : List.copyOf(classificationAreas);
    }
}
