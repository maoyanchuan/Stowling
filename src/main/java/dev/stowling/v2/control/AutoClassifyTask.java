package dev.stowling.v2.control;

import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.TaskPriority;
import java.util.List;
import java.util.UUID;

public record AutoClassifyTask(
        UUID id,
        String name,
        boolean enabled,
        AutoClassifyDetection detection,
        String cacheRegionRef,
        String categoryZoneRef,
        List<CategoryMarkerSource> markerSources,
        TaskPriority collectionPriority,
        TaskPriority classificationPriority,
        String collectionTaskRef,
        String classificationTaskRef,
        String interruptionTaskRef,
        List<UUID> triggerIds
) {
    public AutoClassifyTask {
        markerSources = List.copyOf(markerSources);
        triggerIds = List.copyOf(triggerIds);
    }
}
