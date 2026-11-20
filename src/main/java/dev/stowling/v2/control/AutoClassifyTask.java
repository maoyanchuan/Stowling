package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskCleanupPolicy;

import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.TaskPriority;
import java.util.List;
import java.util.UUID;

public record AutoClassifyTask(
        UUID id,
        String name,
        boolean enabled,
        boolean collectionEnabled,
        boolean classificationEnabled,
        AutoClassifyDetection detection,
        String cacheRegionRef,
        List<AutoClassifyAreaDefinition> classificationAreas,
        TaskPriority collectionPriority,
        TaskPriority classificationPriority,
        String collectionTaskRef,
        String classificationTaskRef,
        String interruptionTaskRef,
        List<UUID> triggerIds,
        boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String unstackableRegionRef
) {
    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheRegionRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority, classificationPriority,
                collectionTaskRef, classificationTaskRef, interruptionTaskRef, triggerIds,
                overflowEnabled, cleanupPolicy, null);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheRegionRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds,
            boolean overflowEnabled
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, overflowEnabled, null);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheRegionRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheRegionRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, false);
    }

    public AutoClassifyTask {
        classificationAreas = List.copyOf(classificationAreas);
        triggerIds = List.copyOf(triggerIds);
    }
}
