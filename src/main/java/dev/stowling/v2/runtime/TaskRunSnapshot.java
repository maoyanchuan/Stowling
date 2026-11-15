package dev.stowling.v2.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskRunSnapshot(
        UUID runId,
        UUID taskId,
        String taskName,
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
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
