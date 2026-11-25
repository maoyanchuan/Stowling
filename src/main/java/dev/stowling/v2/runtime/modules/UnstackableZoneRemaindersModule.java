package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.UnstackableZoneRouting;
import java.util.LinkedHashSet;
import java.util.Set;

final class UnstackableZoneRemaindersModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CategoryZoneDefinition zone;
    private final ResourceReference source;
    private TaskModuleExecutor delegate;

    UnstackableZoneRemaindersModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.source = ResourceReference.parse(
                step.parameters().get(ConfigValidator.CACHE_LOCATION_REF));
        this.zone = ResourceReferenceSupport.categoryZone(context,
                ResourceReferenceSupport.reference(step, ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE));
    }

    static TaskModuleExecutor create(TaskExecutionContext context, TaskStep step) {
        return new UnstackableZoneRemaindersModule(context, step);
    }

    @Override
    public TaskModuleResult start() {
        if (!UnstackableZoneRouting.isDedicated(zone)) {
            return TaskModuleResult.succeeded("通用分类区保持原有余物处理");
        }
        context.workspace().endClassificationDelivery();
        var scan = context.workspace().containerScan().orElse(null);
        if (scan == null || !scan.succeeded()) {
            return TaskModuleResult.failed("不可堆叠区域余物处理缺少有效来源盘点");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (ContainerTarget cache : scan.containers()) {
            keys.addAll(context.storageAutomation().unstackableItems(context, cache));
        }
        keys.addAll(context.storageAutomation().carriedUnstackableItems(context));
        context.workspace().claimUnstackableRemainders(keys);
        if (keys.stream().noneMatch(context.workspace()::activeUnstackableRemainder)) {
            return TaskModuleResult.succeeded("本区没有剩余不可堆叠物品");
        }
        String destination = zone.categoryOptions() == null
                ? "" : zone.categoryOptions().unstackableOverflowRef();
        if (destination.isEmpty()) {
            return TaskModuleResult.succeeded("未配置不可堆叠溢出区，余物保留来源并隔离通用溢出");
        }
        var target = ResourceReference.parse(destination);
        var containers = CacheLocations.scan(context, target, Set.of(), 1_000_000, true);
        if (!containers.succeeded()) return TaskModuleResult.failed(containers.message());
        for (ContainerTarget container : containers.containers()) {
            boolean overlapsSource = scan.containers().stream().anyMatch(cache ->
                    cache.entrances().stream().anyMatch(container.entrances()::contains));
            boolean overlapsStorage = zone.bindings().stream().anyMatch(binding ->
                    container.entrances().stream().anyMatch(position ->
                            binding.storageArea().contains(position)));
            if (overlapsSource || overlapsStorage) {
                return TaskModuleResult.failed("不可堆叠溢出区必须独立于来源及分类储存容器");
            }
        }
        delegate = context.storageAutomation().routeClassificationRemainders(context,
                source, target, UnstackableZoneRouting.KIND);
        return delegate.start();
    }

    @Override
    public TaskModuleResult tick() {
        if (delegate == null) throw new IllegalStateException("zone routing already completed");
        return delegate.tick();
    }

    @Override
    public void cancel() {
        if (delegate != null) delegate.cancel();
    }
}
