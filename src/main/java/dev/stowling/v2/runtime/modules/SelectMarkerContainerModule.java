package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;

final class SelectMarkerContainerModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;

    SelectMarkerContainerModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        return context.workspace().selectMarker()
                .map(target -> TaskModuleResult.succeeded(
                        "已选择分类标记容器 " + target.target().position()
                ))
                .orElseGet(() -> TaskModuleResult.failed("没有可选择的分类标记容器"));
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("marker selection already completed");
    }

    @Override
    public void cancel() {
    }
}
