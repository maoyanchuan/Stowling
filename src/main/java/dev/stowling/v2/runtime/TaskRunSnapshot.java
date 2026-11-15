package dev.stowling.v2.runtime;

import dev.stowling.v2.model.TaskPriority;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskRunSnapshot(
        UUID runId,
        UUID taskId,
        String taskName,
        TaskPriority priority,
        boolean repeating,
        TaskRunState state,
        int completedSteps,
        int totalSteps,
        String message,
        Instant startedAt,
        Instant updatedAt
) {
    public TaskRunSnapshot {
        runId = Objects.requireNonNull(runId, "runId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskName = Objects.requireNonNull(taskName, "taskName");
        priority = Objects.requireNonNull(priority, "priority");
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
