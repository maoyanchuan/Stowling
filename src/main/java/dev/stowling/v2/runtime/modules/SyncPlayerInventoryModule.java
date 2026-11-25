package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.model.TaskStep;
import java.util.Set;

final class SyncPlayerInventoryModule implements TaskModuleExecutor {
    private static final int STABLE_TICKS = 40;

    private final TaskExecutionContext context;
    private final Set<String> itemFilter;
    private int stableTicks;
    private int mismatchTicks;

    SyncPlayerInventoryModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        this.itemFilter = TaskModuleSupport.itemFilter(step);
    }

    @Override
    public TaskModuleResult start() {
        context.workspace().endClassificationDelivery();
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        PlayerInventoryManager manager = context.inventory();
        PlayerInventoryManager.Observation observed = manager.observe().orElse(null);
        if (observed == null) {
            return TaskModuleResult.failed("无法读取全局背包快照");
        }
        PlayerInventorySnapshot current = observed.snapshot();
        int recorded = manager.claimUnexpected(context.workspace().inventoryAccount(), itemFilter);
        PlayerInventorySnapshot expected = manager.expectedInventory().orElseThrow();
        if (recorded > 0 || context.workspace().hasManagedItems()) {
            return TaskModuleResult.succeeded(
                    "全局背包有待处理物品，交给当前任务处理"
            );
        }
        if (current.itemCounts().equals(expected.itemCounts())) {
            mismatchTicks = 0;
            stableTicks++;
            if (stableTicks >= STABLE_TICKS) {
                return TaskModuleResult.succeeded(
                        "全局背包账本已核对完成"
                );
            }
            return TaskModuleResult.waiting(
                    "确认真实背包快照稳定 " + stableTicks + "/"
                            + STABLE_TICKS
            );
        }
        stableTicks = 0;
        mismatchTicks++;
        if (mismatchTicks >= STABLE_TICKS) {
            PlayerInventoryDelta remaining = PlayerInventoryDelta.between(
                    expected,
                    current
            );
            return TaskModuleResult.failed(
                    "背包与全局账本不一致；新增="
                            + remaining.added()
                            + "，减少="
                            + remaining.removed()
            );
        }
        return TaskModuleResult.waiting(
                "等待背包与全局账本同步 "
                        + mismatchTicks
                        + "/"
                        + STABLE_TICKS
        );
    }

    @Override
    public void cancel() {
    }
}
