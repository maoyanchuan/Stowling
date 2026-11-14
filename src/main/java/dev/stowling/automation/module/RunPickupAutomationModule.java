package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Objects;

public final class RunPickupAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.RUN_PICKUP,
                    "运行拾取",
                    "留空时执行流程全部区域；可等待选定范围有客户端可见物品。",
                    List.of(
                            new Parameter("workflowId", "拾取流程", "pickup", true),
                            new Parameter(
                                    "regionId",
                                    "拾取范围（可选）",
                                    "pickup-region",
                                    false
                            ),
                            new Parameter(
                                    "waitForItems",
                                    "等待区域有物品再拾取",
                                    "boolean",
                                    false
                            )
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
            boolean waitForItems = ModuleParameters.optional(
                    context.step(),
                    "waitForItems"
            ).map(RunPickupAutomationModule::parseBoolean).orElse(false);
            PickupWorkflowPort.Request request = new PickupWorkflowPort.Request(
                    ModuleParameters.required(context.step(), "workflowId"),
                    ModuleParameters.optional(context.step(), "regionId"),
                    waitForItems
            );
            return OperationModuleSupport.execute(context, request, pickup);
        });
    }

    private static boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "waitForItems must be true or false"
            );
        };
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, pickup);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, pickup);
    }
}
