package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.v2.model.TaskStep;

final class MatchContainerCategoriesModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;

    MatchContainerCategoriesModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        ContainerScanResult scan = context.workspace().containerScan()
                .orElse(null);
        if (scan == null) {
            return TaskModuleResult.failed("缺少上一次容器扫描结果");
        }
        if (!scan.succeeded()) {
            return TaskModuleResult.failed(scan.message());
        }
        ContainerCategoryResult categories = context.containerCategories()
                .match(scan.containers());
        if (!categories.succeeded()) {
            return TaskModuleResult.failed(categories.message());
        }
        context.workspace().setContainerCategories(categories);
        return TaskModuleResult.succeeded(categories.message());
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("category module already completed");
    }

    @Override
    public void cancel() {
    }
}
