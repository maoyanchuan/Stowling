package dev.stowling.v2.control;

import dev.stowling.v2.model.AutoClassifyDefinition.Kind;
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
        String cacheLocationRef,
        List<AutoClassifyAreaDefinition> classificationAreas,
        TaskPriority collectionPriority,
        TaskPriority classificationPriority,
        String collectionTaskRef,
        String classificationTaskRef,
        String interruptionTaskRef,
        List<UUID> triggerIds,
        boolean overflowEnabled,
        TaskCleanupPolicy cleanupPolicy,
        String overflowDestinationRef,
        dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
        Kind kind,
        String followUpTaskRef,
        Integer followUpDelaySeconds,
        boolean followUpEnabled,
        dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy,
        Boolean robotWarehouseMixedBoxes,
        Boolean unstackableZoneRoutingEnabled,
        Boolean foodPauseResumeEnabled
) {
    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef,
            dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind,
            String followUpTaskRef,
            Integer followUpDelaySeconds,
            boolean followUpEnabled,
            dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy,
            Boolean robotWarehouseMixedBoxes,
            Boolean unstackableZoneRoutingEnabled
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, overflowEnabled, cleanupPolicy,
                overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, remainderPolicy,
                robotWarehouseMixedBoxes, unstackableZoneRoutingEnabled, null);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef,
            dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind,
            String followUpTaskRef,
            Integer followUpDelaySeconds,
            boolean followUpEnabled,
            dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy,
            Boolean robotWarehouseMixedBoxes
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, overflowEnabled, cleanupPolicy,
                overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, remainderPolicy,
                robotWarehouseMixedBoxes, null);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef,
            dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind,
            String followUpTaskRef,
            Integer followUpDelaySeconds,
            boolean followUpEnabled,
            dev.stowling.v2.model.ClassificationRemainderPolicy remainderPolicy
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, overflowEnabled, cleanupPolicy,
                overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, remainderPolicy, null);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds,
            boolean overflowEnabled,
            TaskCleanupPolicy cleanupPolicy,
            String overflowDestinationRef,
            dev.stowling.v2.model.CacheIntakePolicy cachePolicy,
            Kind kind,
            String followUpTaskRef,
            Integer followUpDelaySeconds,
            boolean followUpEnabled
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, overflowEnabled, cleanupPolicy,
                overflowDestinationRef, cachePolicy, kind, followUpTaskRef,
                followUpDelaySeconds, followUpEnabled, null);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheLocationRef,
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
                cacheLocationRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, overflowEnabled, null, null, null,
                Kind.COMBINED, null, null, false);
    }

    public AutoClassifyTask(
            UUID id,
            String name,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            AutoClassifyDetection detection,
            String cacheLocationRef,
            List<AutoClassifyAreaDefinition> classificationAreas,
            TaskPriority collectionPriority,
            TaskPriority classificationPriority,
            String collectionTaskRef,
            String classificationTaskRef,
            String interruptionTaskRef,
            List<UUID> triggerIds
    ) {
        this(id, name, enabled, collectionEnabled, classificationEnabled, detection,
                cacheLocationRef, classificationAreas, collectionPriority,
                classificationPriority, collectionTaskRef, classificationTaskRef,
                interruptionTaskRef, triggerIds, false);
    }

    public AutoClassifyTask {
        cachePolicy = cachePolicy == null
                ? dev.stowling.v2.model.CacheIntakePolicy.DEFAULT : cachePolicy;
        classificationAreas = List.copyOf(classificationAreas);
        triggerIds = List.copyOf(triggerIds);
    }
}
