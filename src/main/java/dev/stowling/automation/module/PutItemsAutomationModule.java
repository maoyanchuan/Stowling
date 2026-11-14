package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Objects;

public final class PutItemsAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.PUT_ITEMS,
                    "向容器放物",
                    "把机器人背包中的指定物品放入目标容器。",
                    List.of(
                            new Parameter("containerId", "目标容器", "container", true),
                            new Parameter("itemId", "物品 ID", "item", true),
                            new Parameter("count", "数量", "positive-integer", true)
                    )
            );

    private final ItemTransferPort transfers;

    public PutItemsAutomationModule(ItemTransferPort transfers) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> {
            ItemTransferPort.Request request = new ItemTransferPort.Request(
                    ItemTransferPort.Mode.PUT,
                    "PLAYER",
                    ModuleParameters.required(context.step(), "containerId"),
                    ModuleParameters.required(context.step(), "itemId"),
                    ModuleParameters.positiveInt(context.step(), "count")
            );
            return OperationModuleSupport.execute(context, request, transfers);
        });
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, transfers);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, transfers);
    }
}
