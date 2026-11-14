package dev.stowling.control;

import dev.stowling.automation.AutomationWorkflow;
import java.util.List;
import java.util.Objects;

public record AutomationWorkflowsSnapshot(
        String revision,
        List<AutomationWorkflow> automationWorkflows
) {
    public AutomationWorkflowsSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        automationWorkflows = List.copyOf(Objects.requireNonNull(
                automationWorkflows,
                "automationWorkflows"
        ));
    }
}
