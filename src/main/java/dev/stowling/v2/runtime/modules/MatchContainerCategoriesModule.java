package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategory;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Map<ContainerTarget, Set<String>> accepted = new LinkedHashMap<>();
        for (ContainerCategory category : categories.categories()) {
            accepted.computeIfAbsent(
                    category.container(),
                    ignored -> new LinkedHashSet<>()
            ).add(category.itemId());
        }
        List<ContainerTarget> targets = new ArrayList<>();
        for (ContainerTarget container : scan.containers()) {
            if (accepted.containsKey(container)) {
                targets.add(container);
            }
        }
        context.workspace().setContainerCategories(categories);
        context.workspace().setCategoryMatch(targets, accepted);
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
