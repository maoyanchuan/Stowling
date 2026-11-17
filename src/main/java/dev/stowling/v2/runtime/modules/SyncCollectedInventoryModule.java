package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.model.TaskStep;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SyncCollectedInventoryModule implements TaskModuleExecutor {
    private static final int STABLE_TICKS = 40;

    private final TaskExecutionContext context;
    private final Set<String> itemFilter;
    private int stableTicks;
    private int mismatchTicks;

    SyncCollectedInventoryModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        this.itemFilter = TaskModuleSupport.itemFilter(step);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        PlayerInventorySnapshot initial = context.workspace()
                .initialInventory()
                .orElse(null);
        if (initial == null) {
            return TaskModuleResult.failed("任务没有创建开始前的背包快照");
        }
        Optional<Map<String, Integer>> observed = context.droppedItems()
                .playerItemCounts();
        if (observed.isEmpty()) {
            return TaskModuleResult.failed("无法读取当前背包快照");
        }
        PlayerInventorySnapshot current = new PlayerInventorySnapshot(
                observed.orElseThrow()
        );
        PlayerInventorySnapshot previous = context.workspace()
                .lastInventory()
                .orElse(initial);
        PlayerInventoryDelta delta = PlayerInventoryDelta.between(
                previous,
                current
        );
        int recorded = recordAdditions(delta);
        context.workspace().observeInventory(current.itemCounts());
        if (recorded > 0) {
            return TaskModuleResult.succeeded(
                    "检测到延迟拾取的物品，重新存入缓存"
            );
        }
        if (current.equals(initial)) {
            mismatchTicks = 0;
            stableTicks++;
            if (stableTicks >= STABLE_TICKS) {
                return TaskModuleResult.succeeded(
                        "背包已恢复到任务前快照"
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
                    initial,
                    current
            );
            return TaskModuleResult.failed(
                    "背包与任务前快照不一致；新增="
                            + remaining.added()
                            + "，减少="
                            + remaining.removed()
            );
        }
        return TaskModuleResult.waiting(
                "等待背包恢复到任务前快照 "
                        + mismatchTicks
                        + "/"
                        + STABLE_TICKS
        );
    }

    @Override
    public void cancel() {
    }

    private int recordAdditions(PlayerInventoryDelta delta) {
        int recorded = 0;
        for (Map.Entry<String, Integer> entry : delta.added().entrySet()) {
            if (!TaskModuleSupport.itemMatches(
                    entry.getKey(),
                    itemFilter
            )) {
                continue;
            }
            context.workspace().recordCollected(
                    entry.getKey(),
                    entry.getValue()
            );
            recorded = Math.addExact(recorded, entry.getValue());
        }
        return recorded;
    }
}
