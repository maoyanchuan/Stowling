package dev.stowling.control;

public final class AutomationWorkflowNotFoundException extends RuntimeException {
    public AutomationWorkflowNotFoundException() {
        super("Automation workflow not found");
    }
}
