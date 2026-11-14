package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CheckInventoryAutomationModule implements AutomationModule {
    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
            AutomationStepType.CHECK_INVENTORY,
            "检查库存",
            "读取玩家背包权威快照并核验物品数量。",
            List.of(
                            new Parameter("itemId", "物品 ID", "item", true),
                            new Parameter("count", "期望数量", "positive-integer", true)
    )
    );

    private final InventoryQueryPort inventory;

    public CheckInventoryAutomationModule(InventoryQueryPort inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> check(context));
    }

    private AutomationModuleResult check(AutomationModuleContext context) {
        String itemId = ModuleParameters.required(context.step(), "itemId");
        int required = ModuleParameters.positiveInt(context.step(), "count");
        PlayerInventorySnapshot snapshot = Objects.requireNonNull(
                inventory.snapshot(),
                "inventory snapshot"
        );
        int available = snapshot.itemCounts().getOrDefault(itemId, 0);
        Map<String, String> state = Map.of(
                "itemId", itemId,
                "required", Integer.toString(required),
                "available", Integer.toString(available)
        );
        if (available >= required) {
            return AutomationModuleResult.succeeded(
                    state,
                    "CHECK_INVENTORY:" + itemId + ":" + available
            );
        }
        return AutomationModuleResult.failed(
                "库存不足: 需要 " + required + " 个 " + itemId + "，实际 " + available
        );
    }
}
