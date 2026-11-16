package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ResolveContainerCategoriesModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CategoryZoneDefinition zone;
    private final Set<String> containerTypes;
    private final int maxVolume;

    ResolveContainerCategoriesModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.CATEGORY_ZONE_REF,
                ResourceType.CATEGORY_ZONE,
                TaskModuleIds.CATEGORY_ZONE_ID
        );
        this.zone = ResourceReferenceSupport.categoryZone(context, reference);
        this.containerTypes = TaskModuleSupport.containerTypes(step);
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
    }

    @Override
    public TaskModuleResult start() {
        ContainerScanResult cacheScan = context.workspace().containerScan()
                .orElse(null);
        if (cacheScan == null || !cacheScan.succeeded()) {
            return TaskModuleResult.failed("缺少有效的缓存容器扫描结果");
        }
        Map<BlockPosition, Set<String>> markerContents = new LinkedHashMap<>();
        for (CategoryBindingDefinition binding : zone.bindings()) {
            if (!ContainerContentsCategoryRule.ID.equals(binding.ruleId())) {
                continue;
            }
            Map<BlockPosition, Set<String>> cached = context
                    .categoryMarkerCache()
                    .contents(binding.id())
                    .orElse(null);
            if (cached == null) {
                return TaskModuleResult.failed(
                        "缺少分类容器缓存，请先执行分类标记流程"
                );
            }
            markerContents.putAll(cached);
        }
        CategoryZoneResult result = context.categoryZonesPort().resolve(
                zone,
                containerTypes,
                maxVolume,
                markerContents
        );
        if (!result.succeeded()) {
            return TaskModuleResult.failed(result.message());
        }
        Map<ContainerTarget, Set<String>> accepted = new LinkedHashMap<>();
        List<ContainerTarget> targets = new ArrayList<>();
        for (Map.Entry<String, List<ContainerTarget>> entry
                : result.categories().entrySet()) {
            for (ContainerTarget target : entry.getValue()) {
                accepted.computeIfAbsent(
                        target,
                        ignored -> new LinkedHashSet<>()
                ).add(entry.getKey());
                if (!targets.contains(target)) {
                    targets.add(target);
                }
            }
        }
        context.workspace().setResolvedCategories(
                targets,
                accepted,
                cacheScan.containers()
        );
        return TaskModuleResult.succeeded(
                "已解析 " + accepted.size() + " 个分类目标容器"
        );
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("category resolve already completed");
    }

    @Override
    public void cancel() {
    }
}
