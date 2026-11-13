package dev.stowling.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditResult(UUID taskId, Instant acceptedAt) {
    public AuditResult {
        taskId = Objects.requireNonNull(taskId, "taskId");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
