package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;

final class SelectContainerModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;

    SelectContainerModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        return context.workspace().selectNextContainer()
                .map(target -> TaskModuleResult.succeeded(
                        "已选择容器 " + target.position()
                ))
                .orElseGet(() -> TaskModuleResult.failed("没有可选择的容器"));
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("container selection already completed");
    }

    @Override
    public void cancel() {
    }
}
