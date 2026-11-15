package dev.stowling.v2.runtime;

import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerType;
import java.util.Objects;
import java.util.UUID;

public record TaskTriggerSnapshot(
        UUID triggerId,
        String triggerName,
        TaskTriggerType type,
        UUID targetTaskId,
        String targetTaskName,
        TaskPriority priority,
        TaskTriggerState state,
        String message
) {
    public TaskTriggerSnapshot {
        triggerId = Objects.requireNonNull(triggerId, "triggerId");
        triggerName = Objects.requireNonNull(triggerName, "triggerName");
        type = Objects.requireNonNull(type, "type");
        targetTaskId = Objects.requireNonNull(targetTaskId, "targetTaskId");
        targetTaskName = Objects.requireNonNull(
                targetTaskName,
                "targetTaskName"
        );
        priority = Objects.requireNonNull(priority, "priority");
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
    }
}
