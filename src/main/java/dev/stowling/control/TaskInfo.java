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
        String stage,
        String currentStepId,
        Integer stepIndex,
        Integer stepCount,
        String moduleState,
        String lastConfirmedAction
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
        if (stepIndex != null && stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must not be negative");
        }
        if (stepCount != null && stepCount < 0) {
            throw new IllegalArgumentException("stepCount must not be negative");
        }
        if (stepIndex != null && stepCount != null && stepIndex > stepCount) {
            throw new IllegalArgumentException("stepIndex must not exceed stepCount");
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
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public TaskInfo(
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
        this(
                id,
                kind,
                status,
                automationState,
                createdAt,
                updatedAt,
                failureReason,
                workflowId,
                currentRegionId,
                round,
                stage,
                null,
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
