package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.model.TaskStep;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class SelectStorageTargetModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final String itemSource;
    private final boolean allowCacheFallback;
    private TaskModuleExecutor preparation;

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
        ContainerTarget target = context.workspace()
                .selectStorageTarget(itemSource, allowCacheFallback).orElse(null);
        if (target == null) {
            return TaskModuleResult.failed("没有可用的分类目的容器");
        }
        if (context.workspace().selectedStorageUsesCacheFallback()) {
            return TaskModuleResult.succeeded("分类目的容器不可用，物品将存回缓存 "
                    + target.position());
        }
        if (allowCacheFallback) {
            Set<String> accepted = context.workspace().acceptedItemsByContainer()
                    .getOrDefault(target, Set.of());
            Map<String, Integer> incoming = new LinkedHashMap<>();
            context.workspace().itemsForSource(itemSource).forEach((key, count) -> {
                if (ItemIdentity.matches(key, accepted)) {
                    incoming.put(key, count);
                }
            });
            preparation = context.storageAutomation().prepareDeposit(context, target, incoming)
                    .orElse(null);
        }
        return preparation == null
                ? TaskModuleResult.succeeded("已选择分类目的容器 " + target.position())
                : preparation.start();
    }

    @Override
    public TaskModuleResult tick() {
        if (preparation == null) {
            throw new IllegalStateException("storage selection already completed");
        }
        return preparation.tick();
    }

    @Override
    public void cancel() {
        if (preparation != null) {
            preparation.cancel();
        }
    }

    @Override
    public void requestStop() {
        if (preparation != null) {
            preparation.requestStop();
        }
    }

    @Override
    public boolean safeToStop() {
        return preparation == null || preparation.safeToStop();
    }
}
