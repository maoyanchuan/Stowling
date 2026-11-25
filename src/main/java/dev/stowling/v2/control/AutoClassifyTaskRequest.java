package dev.stowling.v2.control;

import dev.stowling.v2.model.AutoClassifyDefinition.Kind;
import dev.stowling.v2.model.TaskCleanupPolicy;

import dev.stowling.v2.model.TaskPriority;
import java.util.List;

public record AutoClassifyTaskRequest(
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
        Integer followUpDelaySeconds,
        Boolean followUpEnabled,
        dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy,
        Boolean robotWarehouseMixedBoxes,
        Boolean sameCategoryBoxBatching
) {
    public AutoClassifyTaskRequest(
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
            Integer followUpDelaySeconds,
            Boolean followUpEnabled,
            dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy,
            Boolean robotWarehouseMixedBoxes
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, remainderPolicy,
                robotWarehouseMixedBoxes, null);
    }

    public AutoClassifyTaskRequest(
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
            Integer followUpDelaySeconds,
            Boolean followUpEnabled,
            dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, remainderPolicy, null);
    }

    public AutoClassifyTaskRequest(
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
            Integer followUpDelaySeconds,
            Boolean followUpEnabled
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, null);
    }

    public AutoClassifyTaskRequest(String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef, dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind, String followUpTaskRef, Integer followUpDelaySeconds) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, null);
    }

    public AutoClassifyTaskRequest(String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef, dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind, String followUpTaskRef) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, kind, followUpTaskRef, null);
    }

    public AutoClassifyTaskRequest(String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef, dev.stowling.v2.model.CacheIntakePolicy cachePolicy) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, cachePolicy, null, null);
    }

    public AutoClassifyTaskRequest(String name, Boolean enabled,
            Boolean collectionEnabled, Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection, String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas, TaskPriority collectionPriority,
            Boolean overflowEnabled, TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, overflowDestinationRef, null);
    }

    public AutoClassifyTaskRequest(
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
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled,
                cleanupPolicy, null);
    }

    public AutoClassifyTaskRequest(
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
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, overflowEnabled, null);
    }

    public AutoClassifyTaskRequest(
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheLocationRef,
            List<AutoClassifyAreaRequest> classificationAreas,
            TaskPriority collectionPriority
    ) {
        this(name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority, null);
    }

    public AutoClassifyTaskRequest {
        classificationAreas = classificationAreas == null
                ? List.of()
                : List.copyOf(classificationAreas);
    }
}
