package dev.stowling.task;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TaskSnapshot(
        UUID id,
        TaskKind kind,
        TaskStatus status,
        AutomationState automationState,
        Instant createdAt,
        Instant updatedAt,
        Optional<String> failureReason
) {
    public TaskSnapshot {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        status = Objects.requireNonNull(status, "status");
        automationState = Objects.requireNonNull(automationState, "automationState");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        failureReason = Objects.requireNonNull(failureReason, "failureReason");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }
}
