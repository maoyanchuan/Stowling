package dev.stowling.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskInfo(
        UUID id,
        String kind,
        String status,
        String automationState,
        Instant createdAt,
        Instant updatedAt,
        String failureReason,
        String workflowId,
        String currentRegionId,
        Long round,
        String stage
) {
    public TaskInfo {
        id = Objects.requireNonNull(id, "id");
        kind = requireText(kind, "kind");
        status = requireText(status, "status");
        automationState = requireText(automationState, "automationState");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (round != null && round < 0) {
            throw new IllegalArgumentException("round must not be negative");
        }
    }

    public TaskInfo(
            UUID id,
            String kind,
            String status,
            String automationState,
            Instant createdAt,
            Instant updatedAt,
            String failureReason
    ) {
        this(
                id,
                kind,
                status,
                automationState,
                createdAt,
                updatedAt,
                failureReason,
                null,
                null,
                null,
                null
        );
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
