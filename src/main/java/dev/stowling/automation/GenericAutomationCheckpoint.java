package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GenericAutomationCheckpoint(
        UUID taskId,
        String workflowId,
        int stepIndex,
        Map<String, String> moduleState,
        Optional<String> lastConfirmedAction,
        AutomationExecutionStatus status
) {
    public GenericAutomationCheckpoint {
        taskId = Objects.requireNonNull(taskId, "taskId");
        workflowId = Objects.requireNonNull(workflowId, "workflowId").trim();
        if (workflowId.isEmpty()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must not be negative");
        }
        moduleState = Map.copyOf(Objects.requireNonNull(moduleState, "moduleState"));
        lastConfirmedAction = Objects.requireNonNull(
                lastConfirmedAction,
                "lastConfirmedAction"
        );
        status = Objects.requireNonNull(status, "status");
    }
}
