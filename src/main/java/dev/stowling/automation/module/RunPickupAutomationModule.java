package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.PickupWorkflowPort;
import java.util.List;
import java.util.Objects;

public final class RunPickupAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
            AutomationStepType.RUN_PICKUP,
            "运行拾取",
            "运行完整拾取流程或指定一个区域。",
            List.of(
                            new Parameter("workflowId", "拾取流程", "pickup", true),
                            new Parameter("regionId", "只运行区域", "pickup-region", false)
    )
    );

    private final PickupWorkflowPort pickup;

    public RunPickupAutomationModule(PickupWorkflowPort pickup) {
        this.pickup = Objects.requireNonNull(pickup, "pickup");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> {
            PickupWorkflowPort.Request request = new PickupWorkflowPort.Request(
                    ModuleParameters.required(context.step(), "workflowId"),
                    ModuleParameters.optional(context.step(), "regionId")
            );
            return OperationModuleSupport.execute(context, request, pickup);
        });
    }
}
