package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.ItemTransferPort;
import java.util.List;
import java.util.Objects;

public final class TransferItemsAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
            AutomationStepType.TRANSFER_ITEMS,
            "容器搬运",
            "在两个容器之间搬移指定物品。",
            List.of(
                            new Parameter("sourceContainerId", "来源容器", "container", true),
                            new Parameter("destination", "目标容器", "container", true),
                            new Parameter("itemId", "物品 ID", "item", true),
                            new Parameter("count", "数量", "positive-integer", true)
    )
    );

    private final ItemTransferPort transfers;

    public TransferItemsAutomationModule(ItemTransferPort transfers) {
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
                    ItemTransferPort.Mode.TRANSFER,
                    ModuleParameters.required(
                    context.step(),
                    "sourceContainerId"
            ),
                    ModuleParameters.required(context.step(), "destination"),
                    ModuleParameters.required(context.step(), "itemId"),
                    ModuleParameters.positiveInt(context.step(), "count")
            );
            return OperationModuleSupport.execute(context, request, transfers);
        });
    }
}
