package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;
import java.util.Map;

final class AssertNoManagedItemsModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;

    AssertNoManagedItemsModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        Map<String, Integer> managed = context.workspace().managedItems();
        if (managed.isEmpty()) {
            return TaskModuleResult.succeeded("任务物品已全部处理");
        }
        return TaskModuleResult.failed(
                "任务结束仍有受管物品: " + managed
        );
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("assertion already completed");
    }

    @Override
    public void cancel() {
    }
}
