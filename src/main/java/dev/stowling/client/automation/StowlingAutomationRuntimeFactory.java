package dev.stowling.client.automation;

import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.automation.GenericAutomationEngine;
import dev.stowling.automation.module.DefaultAutomationModuleRegistryFactory;
import dev.stowling.control.AutomationRuntimeFactory;
import dev.stowling.persistence.SqliteGenericAutomationCheckpointStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class StowlingAutomationRuntimeFactory
        implements AutomationRuntimeFactory {
    private final DefaultAutomationModuleRegistryFactory moduleFactory;
    private final SqliteGenericAutomationCheckpointStore checkpoints;
    private final List<AutomationModuleDescriptor> modules;

    public StowlingAutomationRuntimeFactory(
            DefaultAutomationModuleRegistryFactory moduleFactory,
            SqliteGenericAutomationCheckpointStore checkpoints
    ) {
        this.moduleFactory = Objects.requireNonNull(moduleFactory, "moduleFactory");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        modules = List.copyOf(moduleFactory.create().descriptors());
    }

    @Override
    public GenericAutomationEngine create(
            UUID taskId,
            AutomationWorkflow workflow
    ) {
        return new GenericAutomationEngine(
                Objects.requireNonNull(taskId, "taskId"),
                Objects.requireNonNull(workflow, "workflow"),
                moduleFactory.create(),
                checkpoints
        );
    }

    @Override
    public List<AutomationModuleDescriptor> modules() {
        return modules;
    }
}
