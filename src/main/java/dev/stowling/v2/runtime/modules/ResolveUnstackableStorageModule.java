package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ResolveUnstackableStorageModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final RegionDefinition region;
    private final Set<String> containerTypes;

    ResolveUnstackableStorageModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.region = ResourceReferenceSupport.region(context, ResourceReferenceSupport.reference(
                step, TaskModuleIds.REGION_REF, ResourceType.REGION));
        this.containerTypes = TaskModuleSupport.containerTypes(step);
    }

    @Override
    public TaskModuleResult start() {
        TaskRunWorkspace workspace = context.workspace();
        ContainerScanResult cacheScan = workspace.containerScan().orElse(null);
        if (cacheScan == null || !cacheScan.succeeded()) {
            return TaskModuleResult.failed("不可堆叠分类需要先扫描并盘点缓存容器");
        }
        Set<String> selectors = new LinkedHashSet<>();
        List<ContainerTarget> sources = new ArrayList<>();
        for (ContainerTarget cache : cacheScan.containers()) {
            Set<String> keys = context.storageAutomation().unstackableItems(context, cache);
            if (!keys.isEmpty()) {
                sources.add(cache);
                keys.stream().map(ItemIdentity::exact).forEach(selectors::add);
            }
        }
        if (selectors.isEmpty()) {
            workspace.setResolvedCategories(List.of(), Map.of(), List.of());
            return TaskModuleResult.succeeded("缓存中没有需要分流的不可堆叠物品");
        }
        ContainerScanResult storage = context.containerRegions().scan(
                region, containerTypes, TaskModuleSupport.DEFAULT_SCAN_VOLUME);
        if (!storage.succeeded()) {
            return TaskModuleResult.failed(storage.message());
        }
        Set<BlockPosition> cachePositions = cacheScan.containers().stream()
                .flatMap(cache -> cache.entrances().stream()).collect(Collectors.toSet());
        List<ContainerTarget> targets = storage.containers().stream()
                .filter(target -> target.entrances().stream().noneMatch(cachePositions::contains))
                .toList();
        if (targets.isEmpty()) {
            workspace.setResolvedCategories(List.of(), Map.of(), List.of());
            return TaskModuleResult.succeeded("不可堆叠储存区没有独立容器，物品留在缓存");
        }

        Map<ContainerTarget, Set<String>> accepted = new LinkedHashMap<>();
        Set<String> exactItems = Set.copyOf(selectors);
        targets.forEach(target -> accepted.put(target, exactItems));
        workspace.setUnstackableClassification(targets, accepted, sources);
        return TaskModuleResult.succeeded("已解析 " + targets.size() + " 个不可堆叠储存容器");
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("unstackable storage resolve already completed");
    }

    @Override
    public void cancel() {
    }
}
