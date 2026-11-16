package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;

final class SelectStorageTargetModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;

    SelectStorageTargetModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        return context.workspace().selectStorageTarget()
                .map(target -> TaskModuleResult.succeeded(
                        "已选择分类目的容器 " + target.position()
                ))
                .orElseGet(() -> TaskModuleResult.failed("没有可用的分类目的容器"));
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("storage selection already completed");
    }

    @Override
    public void cancel() {
    }
}
