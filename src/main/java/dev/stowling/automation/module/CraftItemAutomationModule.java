package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.CraftingPort;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Objects;

public final class CraftItemAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.CRAFT_ITEM,
                    "制作物品",
                    "使用当前配方制作指定物品并等待权威确认。",
                    List.of(
                            new Parameter("itemId", "制作物品 ID", "item", true),
                            new Parameter("count", "制作数量", "positive-integer", true)
                    )
            );

    private final CraftingPort crafting;

    public CraftItemAutomationModule(CraftingPort crafting) {
        this.crafting = Objects.requireNonNull(crafting, "crafting");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> {
            CraftingPort.Request request = new CraftingPort.Request(
                    ModuleParameters.required(context.step(), "itemId"),
                    ModuleParameters.positiveInt(context.step(), "count")
            );
            return OperationModuleSupport.execute(context, request, crafting);
        });
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, crafting);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, crafting);
    }
}
