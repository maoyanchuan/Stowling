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
                context.workspace().initialInventory();
        if (initial.isEmpty()) {
            return TaskModuleResult.failed(
                    "任务没有创建开始前的背包快照"
            );
        }
        Optional<Map<String, Integer>> observed =
                context.droppedItems().playerItemCounts();
        if (observed.isEmpty()) {
            return TaskModuleResult.failed("无法读取任务结束时的背包快照");
        }
        PlayerInventorySnapshot expected = initial.orElseThrow();
        PlayerInventorySnapshot current = new PlayerInventorySnapshot(
                observed.orElseThrow()
        );
        if (expected.equals(current)) {
            return TaskModuleResult.succeeded("任务结束时背包已恢复到任务前快照");
        }
        mismatchedTicks++;
        if (mismatchedTicks >= MAX_SETTLE_TICKS) {
            PlayerInventoryDelta delta = PlayerInventoryDelta.between(
                    expected,
                    current
            );
            return TaskModuleResult.failed(
                    "任务结束时背包不符合预期；新增="
                            + delta.added()
                            + "，减少="
                            + delta.removed()
            );
        }
        return TaskModuleResult.waiting(
                "等待背包恢复到任务前快照 "
                        + mismatchedTicks
                        + "/"
                        + MAX_SETTLE_TICKS
        );
    }

    @Override
    public void cancel() {
    }
}
