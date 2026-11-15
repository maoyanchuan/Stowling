package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerType;
import java.util.UUID;

public record TaskTriggerRequest(
        String name,
        TaskTriggerType type,
        UUID targetTaskId,
        TaskPriority priority,
        int intervalTicks,
        int cooldownTicks,
        UUID coordinateId,
        String itemFilter,
        Integer foodLevelThreshold
) {
}
