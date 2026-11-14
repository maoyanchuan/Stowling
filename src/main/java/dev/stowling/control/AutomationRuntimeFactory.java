package dev.stowling.control;

import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.automation.GenericAutomationEngine;
import java.util.List;
import java.util.UUID;

public interface AutomationRuntimeFactory {
    GenericAutomationEngine create(UUID taskId, AutomationWorkflow workflow);

    List<AutomationModuleDescriptor> modules();
}
