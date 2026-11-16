package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategory;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class CategorySortPlan {
    private CategorySortPlan() {
    }

    static Plan load(
            TaskExecutionContext context,
            TaskStep step,
            CategoryZoneDefinition categoryZone
    ) {
        RegionDefinition cacheRegion = TaskModuleSupport.region(
                context,
                step,
                ConfigValidator.CACHE_REGION_ID
        );
        Set<String> containerTypes = TaskModuleSupport.containerTypes(step);
        int maxVolume = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
        return categoryZone == null
                ? loadCategories(
                        context,
                        cacheRegion,
                        containerTypes,
                        maxVolume
                )
                : loadCategoryZone(
                        context,
                        categoryZone,
                        cacheRegion,
                        containerTypes,
                        maxVolume
                );
    }

    private static Plan loadCategories(
            TaskExecutionContext context,
            RegionDefinition cacheRegion,
            Set<String> containerTypes,
            int maxVolume
    ) {
        ContainerScanResult targetScan = context.workspace().containerScan()
                .orElse(null);
        ContainerCategoryResult categoryResult = context.workspace()
                .containerCategories()
                .orElse(null);
        if (targetScan == null) {
            return Plan.failure("缺少上一次目标容器扫描结果");
        }
        if (!targetScan.succeeded()) {
            return Plan.failure(targetScan.message());
        }
        if (categoryResult == null) {
            return Plan.failure("缺少上一次容器分类结果");
        }
        if (!categoryResult.succeeded()) {
            return Plan.failure(categoryResult.message());
        }
        Map<ContainerTarget, Set<String>> accepted =
                new LinkedHashMap<>();
        for (ContainerCategory category : categoryResult.categories()) {
            accepted.computeIfAbsent(
                    category.container(),
                    ignored -> new LinkedHashSet<>()
            ).add(category.itemId());
        }
        return complete(
                accepted,
                targetScan.containers(),
                cacheRegion,
                containerTypes,
                maxVolume,
                context
        );
    }

    private static Plan loadCategoryZone(
            TaskExecutionContext context,
            CategoryZoneDefinition zone,
            RegionDefinition cacheRegion,
            Set<String> containerTypes,
            int maxVolume
    ) {
        Map<BlockPosition, Set<String>> markerContents =
                new LinkedHashMap<>();
        for (CategoryBindingDefinition binding : zone.bindings()) {
            if (!ContainerContentsCategoryRule.ID.equals(
                    binding.ruleId()
            )) {
                continue;
            }
            Map<BlockPosition, Set<String>> cached =
                    context.categoryMarkerCache()
                            .contents(binding.id())
                            .orElse(null);
            if (cached == null) {
                return Plan.failure(
                        "缺少分类容器缓存，请先运行“刷新分类容器缓存”"
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
            return Plan.failure(result.message());
        }
        Map<ContainerTarget, Set<String>> accepted =
                new LinkedHashMap<>();
        List<ContainerTarget> targetContainers = new ArrayList<>();
        for (Map.Entry<String, List<ContainerTarget>> entry
                : result.categories().entrySet()) {
            for (ContainerTarget container : entry.getValue()) {
                accepted.computeIfAbsent(
                        container,
                        ignored -> new LinkedHashSet<>()
                ).add(entry.getKey());
                if (!targetContainers.contains(container)) {
                    targetContainers.add(container);
                }
            }
        }
        return complete(
                accepted,
                targetContainers,
                cacheRegion,
                containerTypes,
                maxVolume,
                context
        );
    }

    private static Plan complete(
            Map<ContainerTarget, Set<String>> accepted,
            List<ContainerTarget> targetContainers,
            RegionDefinition cacheRegion,
            Set<String> containerTypes,
            int maxVolume,
            TaskExecutionContext context
    ) {
        ContainerScanResult cacheScan = context.containerRegions().scan(
                cacheRegion,
                containerTypes,
                maxVolume
        );
        if (!cacheScan.succeeded()) {
            return Plan.failure(cacheScan.message());
        }
        Set<ContainerTarget> targetSet = Set.copyOf(targetContainers);
        List<ContainerTarget> cacheContainers = cacheScan.containers().stream()
                .filter(target -> !targetSet.contains(target))
                .toList();
        if (cacheContainers.size() != cacheScan.containers().size()) {
            return Plan.failure("缓存区域与目标区域不能重叠");
        }
        return new Plan(
                accepted.values().stream()
                        .flatMap(Set::stream)
                        .collect(Collectors.toUnmodifiableSet()),
                List.copyOf(targetContainers),
                copyAcceptedItems(accepted),
                cacheContainers,
                ""
        );
    }

    private static Map<ContainerTarget, Set<String>> copyAcceptedItems(
            Map<ContainerTarget, Set<String>> accepted
    ) {
        Map<ContainerTarget, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<ContainerTarget, Set<String>> entry
                : accepted.entrySet()) {
            result.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    record Plan(
            Set<String> categoryItemIds,
            List<ContainerTarget> targetContainers,
            Map<ContainerTarget, Set<String>> acceptedItemsByContainer,
            List<ContainerTarget> cacheTargets,
            String error
    ) {
        static Plan failure(String message) {
            return new Plan(
                    Set.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    message
            );
        }
    }
}
