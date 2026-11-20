package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.model.TaskStep;
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
        var observed = context.inventory().observe();
        if (observed.isEmpty()) {
            return TaskModuleResult.failed("无法读取任务结束时的背包快照");
        }
        PlayerInventoryAccount account = context.workspace().inventoryAccount();
        // A point's late receipts already have an owner and will trigger the next batch.
        int latePickups = account.collectionTask() == null
                ? context.inventory().claimCollectionItems(account, java.util.Set.of()) : 0;
        if (latePickups > 0) {
            return TaskModuleResult.failed("结束核对时仍有刚拾取的任务物品，交给收尾存入指定位置");
        }
        context.inventory().reconcileUnassignedItems();
        PlayerInventorySnapshot expected = context.inventory().expectedInventory().orElseThrow();
        PlayerInventorySnapshot current = observed.orElseThrow().snapshot();
        if (expected.itemCounts().equals(current.itemCounts())) {
            return TaskModuleResult.succeeded("任务结束时背包与全局账本一致");
        }
        mismatchedTicks++;
        if (mismatchedTicks >= MAX_SETTLE_TICKS) {
            PlayerInventoryDelta delta = PlayerInventoryDelta.between(
                    expected,
                    current
            );
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
