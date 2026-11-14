package dev.stowling.control;

import dev.stowling.automation.AutomationWorkflow;
import java.util.List;
import java.util.Objects;

public record AutomationWorkflowUpdateRequest(
        String revision,
        List<AutomationWorkflow> automationWorkflows
) {
    public AutomationWorkflowUpdateRequest {
        revision = Objects.requireNonNull(revision, "revision").trim();
        if (revision.isEmpty()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
        automationWorkflows = List.copyOf(Objects.requireNonNull(
                automationWorkflows,
                "automationWorkflows"
        ));
    }
}
