package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;

final class SelectStorageTargetModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final String itemSource;
    private final boolean allowCacheFallback;

    SelectStorageTargetModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        String configured = ResourceReferenceSupport.optionalParameter(
                step,
                TaskModuleIds.ITEM_SOURCE
        );
        this.itemSource = configured == null || configured.isBlank()
                ? "carried"
                : configured;
        this.allowCacheFallback = TaskModuleSupport.booleanParameter(
                step,
                TaskModuleIds.ALLOW_CACHE_FALLBACK,
                false
        );
    }

    @Override
    public TaskModuleResult start() {
        return context.workspace()
                .selectStorageTarget(itemSource, allowCacheFallback)
                .map(target -> TaskModuleResult.succeeded(
                        context.workspace().selectedStorageUsesCacheFallback()
                                ? "分类目的容器不可用，物品将存回缓存 "
                                        + target.position()
                                : "已选择分类目的容器 " + target.position()
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
