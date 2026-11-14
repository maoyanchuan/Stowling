package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.MaterialAcquisitionPort;
import dev.stowling.schedule.StopOutcome;
import java.util.List;
import java.util.Objects;

public final class AcquireMaterialsAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.ACQUIRE_MATERIALS,
                    "获取缺少原料",
                    "按稳定操作键补齐指定制作原料。",
                    List.of(
                            new Parameter("itemId", "目标物品 ID", "item", true),
                            new Parameter("count", "缺少数量", "positive-integer", true)
                    )
            );

    private final MaterialAcquisitionPort materials;

    public AcquireMaterialsAutomationModule(MaterialAcquisitionPort materials) {
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> {
            MaterialAcquisitionPort.Request request =
                    new MaterialAcquisitionPort.Request(
                    ModuleParameters.required(context.step(), "itemId"),
                    ModuleParameters.positiveInt(context.step(), "count")
            );
            return OperationModuleSupport.execute(context, request, materials);
        });
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return OperationModuleSupport.stop(context, materials);
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return OperationModuleSupport.pollStop(context, materials);
    }
}
