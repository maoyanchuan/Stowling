package dev.stowling.persistence;

import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TaskRecoveryPlan(
        UUID taskId,
        TaskKind kind,
        TaskStatus previousStatus,
        AutomationState resumeState,
        Instant lastUpdatedAt,
        Optional<String> checkpoint,
        int retryCount
) {
    public TaskRecoveryPlan {
        taskId = Objects.requireNonNull(taskId, "taskId");
        kind = Objects.requireNonNull(kind, "kind");
        previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
        resumeState = Objects.requireNonNull(resumeState, "resumeState");
        lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
    }

    public static TaskRecoveryPlan from(PersistedTask task) {
        Objects.requireNonNull(task, "task");
        TaskSnapshot snapshot = task.snapshot();
        return new TaskRecoveryPlan(
                snapshot.id(),
                snapshot.kind(),
                snapshot.status(),
                snapshot.automationState(),
                snapshot.updatedAt(),
                task.recoveryCheckpoint(),
                task.retryCount()
        );
    }
}
