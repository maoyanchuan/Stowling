package dev.stowling.control;

import java.util.List;

public interface AutomationControl {
    List<AutomationModuleInfo> automationModules();

    TaskInfo requestAutomation(AutomationTaskRequest request);

    AutomationWorkflowsSnapshot automationWorkflows();

    AutomationWorkflowsSnapshot updateAutomationWorkflows(
            AutomationWorkflowUpdateRequest request
    );
}
