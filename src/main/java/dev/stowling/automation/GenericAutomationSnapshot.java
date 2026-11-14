package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GenericAutomationSnapshot(
        UUID taskId,
        String workflowId,
        AutomationExecutionStatus status,
        int stepIndex,
        int stepCount,
        Map<String, String> moduleState,
        Optional<String> lastConfirmedAction,
        Optional<String> message
) {
    public GenericAutomationSnapshot {
        taskId = Objects.requireNonNull(taskId, "taskId");
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        status = Objects.requireNonNull(status, "status");
        if (stepIndex < 0 || stepCount < 0 || stepIndex > stepCount) {
            throw new IllegalArgumentException("invalid automation step progress");
        }
        moduleState = Map.copyOf(Objects.requireNonNull(moduleState, "moduleState"));
        lastConfirmedAction = Objects.requireNonNull(
                lastConfirmedAction,
                "lastConfirmedAction"
        );
        message = Objects.requireNonNull(message, "message");
    }
}
