package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ClassificationLayoutEntry;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.UnstackableCategoryRule;
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
    private final boolean allowEmptyContainerScan;
    private final Set<CategoryMarkerSource> markerSources;

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
        CategoryZoneDefinition configuredZone =
                ResourceReferenceSupport.categoryZone(context, reference);
        this.zone = TaskModuleSupport.categoryZoneSelection(
                step,
                configuredZone
        );
        this.containerTypes = TaskModuleSupport.containerTypes(step);
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
        this.allowEmptyContainerScan = TaskModuleSupport.booleanParameter(
                step,
                TaskModuleIds.ALLOW_EMPTY_CONTAINER_SCAN,
                false
        );
        this.markerSources = TaskModuleSupport.optionalMarkerSources(step);
    }

    @Override
    public TaskModuleResult start() {
        ContainerScanResult cacheScan = context.workspace().containerScan()
                .orElse(null);
        if (cacheScan == null && !allowEmptyContainerScan) {
            return TaskModuleResult.failed("缺少有效的缓存容器扫描结果");
        }
        if (cacheScan != null && !cacheScan.succeeded()) {
            return TaskModuleResult.failed(cacheScan.message());
        }
        Map<BlockPosition, Set<String>> markerContents = new LinkedHashMap<>();
        if (requiresContainerContents()) {
            for (CategoryBindingDefinition binding : zone.bindings()) {
                if (UnstackableCategoryRule.ID.equals(binding.ruleId())) {
                    continue;
                }
                if (markerSources == null && !ContainerContentsCategoryRule.ID.equals(
                        binding.ruleId())) {
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
        }
        CategoryZoneResult result = markerSources == null
                ? context.categoryZonesPort().resolve(
                        zone,
                        containerTypes,
                        maxVolume,
                        markerContents
                )
                : context.categoryZonesPort().resolve(
                        zone,
                        containerTypes,
                        maxVolume,
                        markerContents,
                        markerSources
                );
        if (!result.succeeded()) {
            return TaskModuleResult.failed(result.message());
        }
        if (result.categories().containsKey(UnstackableCategoryRule.CATEGORY)) {
            result = expandUnstackableCategories(result, cacheScan);
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
        Set<String> categories = result.categories().keySet();
        List<ContainerTarget> cacheTargets = new ArrayList<>();
        if (cacheScan != null) {
            cacheScan.containers().stream()
                    .filter(target -> context.workspace()
                            .hasRecordedClassifiableStock(target, categories))
                    .forEach(cacheTargets::add);
        }
        for (ContainerTarget temporary : context.storageAutomation().recoveryContainers(context)) {
            if (!context.workspace().hasRecordedClassifiableStock(temporary, categories)) continue;
            if (cacheTargets.stream().noneMatch(value ->
                    value.position().equals(temporary.position()))
                    && targets.stream().noneMatch(value ->
                            value.position().equals(temporary.position()))) {
                cacheTargets.add(temporary);
            }
        }
        List<ContainerTarget> partialBoxes = context.storageAutomation()
                .partialBoxSources(context, targets);
        Set<BlockPosition> partialBoxPositions = new LinkedHashSet<>();
        partialBoxes.forEach(target -> partialBoxPositions.addAll(target.entrances()));
        context.workspace().setResolvedCategories(
                targets,
                accepted,
                dev.stowling.automation.PartialBoxSourcePlan.combine(cacheTargets,
                        partialBoxes, targets),
                result.layout(),
                partialBoxPositions
        );
        return TaskModuleResult.succeeded(
                "已解析 " + accepted.size() + " 个分类目标容器"
        );
    }

    private CategoryZoneResult expandUnstackableCategories(CategoryZoneResult result,
            ContainerScanResult cacheScan) {
        Set<String> selectors = new LinkedHashSet<>();
        Set<BlockPosition> cachePositions = new LinkedHashSet<>();
        if (cacheScan != null) {
            for (ContainerTarget cache : cacheScan.containers()) {
                cachePositions.addAll(cache.entrances());
                context.storageAutomation().unstackableItems(context, cache).stream()
                        .map(ItemIdentity::exact).forEach(selectors::add);
            }
        }
        Map<String, List<ContainerTarget>> categories = new LinkedHashMap<>();
        List<ClassificationLayoutEntry> layout = new ArrayList<>();
        for (ClassificationLayoutEntry entry : result.layout()) {
            if (!UnstackableCategoryRule.CATEGORY.equals(entry.itemId())) {
                categories.computeIfAbsent(entry.itemId(), ignored -> new ArrayList<>())
                        .addAll(entry.containers());
                layout.add(entry);
                continue;
            }
            List<ContainerTarget> targets = entry.containers().stream()
                    .filter(target -> target.entrances().stream().noneMatch(cachePositions::contains))
                    .toList();
            if (targets.isEmpty()) {
                continue;
            }
            for (String selector : selectors) {
                categories.computeIfAbsent(selector, ignored -> new ArrayList<>()).addAll(targets);
                layout.add(new ClassificationLayoutEntry(selector, targets));
            }
        }
        return CategoryZoneResult.success(categories, layout);
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("category resolve already completed");
    }

    @Override
    public void cancel() {
    }

    private boolean requiresContainerContents() {
        if (markerSources != null) {
            return markerSources.contains(
                    CategoryMarkerSource.CONTAINER_CONTENTS
            );
        }
        return zone.bindings().stream().anyMatch(binding ->
                ContainerContentsCategoryRule.ID.equals(binding.ruleId())
        );
    }
}
