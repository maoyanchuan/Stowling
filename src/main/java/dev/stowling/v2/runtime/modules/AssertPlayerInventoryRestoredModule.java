package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.model.TaskStep;
import java.util.Map;
import java.util.Optional;

final class AssertPlayerInventoryRestoredModule
        implements TaskModuleExecutor {
    private static final int MAX_SETTLE_TICKS = 20;

    private final TaskExecutionContext context;
    private int mismatchedTicks;

    AssertPlayerInventoryRestoredModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        Optional<PlayerInventorySnapshot> initial =
                context.inventory().expectedInventory();
        if (initial.isEmpty()) {
            return TaskModuleResult.failed(
                    "全局背包尚未建立基准快照"
            );
        }
        Optional<Map<String, Integer>> observed =
                context.droppedItems().playerItemCounts();
        if (observed.isEmpty()) {
            return TaskModuleResult.failed("无法读取任务结束时的背包快照");
        }
        context.inventory().observe();
        int latePickups = context.inventory().claimObservedPickups(
                context.workspace().inventoryAccount(), java.util.Set.of());
        if (latePickups > 0) {
            return TaskModuleResult.failed("结束核对时仍有刚拾取的任务物品，交给收尾存入指定位置");
        }
        context.inventory().protectUntrackedItems();
        PlayerInventorySnapshot expected = context.inventory().expectedInventory().orElseThrow();
        PlayerInventorySnapshot current = new PlayerInventorySnapshot(
                observed.orElseThrow()
        );
        if (expected.equals(current)) {
            return TaskModuleResult.succeeded("任务结束时背包与全局账本一致");
        }
        mismatchedTicks++;
        if (mismatchedTicks >= MAX_SETTLE_TICKS) {
            PlayerInventoryDelta delta = PlayerInventoryDelta.between(
                    expected,
                    current
            );
            context.inventory().observe();
            context.inventory().claimUnexpected(
                    context.workspace().inventoryAccount(), java.util.Set.of()
            );
            return TaskModuleResult.failed(
                    "任务结束时背包不符合预期；新增="
                            + delta.added()
                            + "，减少="
                            + delta.removed()
            );
        }
        return TaskModuleResult.waiting(
                "等待背包与全局账本同步 "
                        + mismatchedTicks
                        + "/"
                        + MAX_SETTLE_TICKS
        );
    }

    @Override
    public void cancel() {
    }
}
