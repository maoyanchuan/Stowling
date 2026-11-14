package dev.stowling.control;

import java.util.Objects;

public record AutomationTaskRequest(String workflowId) {
    public AutomationTaskRequest {
        Objects.requireNonNull(workflowId, "workflowId");
        workflowId = workflowId.trim();
        if (workflowId.isEmpty()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
    }
}
