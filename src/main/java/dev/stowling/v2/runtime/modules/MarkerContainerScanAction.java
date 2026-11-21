package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MarkerContainerScanAction {
    private MarkerContainerScanAction() {
    }

    static Plan scan(
            TaskExecutionContext context,
            CategoryZoneDefinition zone,
            int maxVolume
    ) {
        return scan(context, zone, maxVolume, null);
    }

    static Plan scan(
            TaskExecutionContext context,
            CategoryZoneDefinition zone,
            int maxVolume,
            Set<CategoryMarkerSource> markerSources
    ) {
        if (markerSources != null && !markerSources.contains(
                CategoryMarkerSource.CONTAINER_CONTENTS
        )) {
            return new Plan(Map.of(), List.of(), "");
        }
        Map<UUID, Map<BlockPosition, Set<String>>> cache =
                new LinkedHashMap<>();
        Map<BlockPosition, Target> byPosition = new LinkedHashMap<>();
        for (CategoryBindingDefinition binding : zone.bindings()) {
            if (UnstackableCategoryRule.ID.equals(binding.ruleId())) {
                continue;
            }
            if (markerSources == null && !ContainerContentsCategoryRule.ID.equals(
                    binding.ruleId()
            )) {
                continue;
            }
            cache.put(binding.id(), new LinkedHashMap<>());
            ContainerScanResult scan = context.containerRegions().scan(
                    markerRegion(binding),
                    ContainerContentsCategoryRule.CONTAINER_TYPES,
                    maxVolume
            );
            if (!scan.succeeded()) {
                return Plan.failure(scan.message());
            }
            for (ContainerTarget target : scan.containers()) {
                Target marker = byPosition.computeIfAbsent(
                        target.position(),
                        ignored -> new Target(target)
                );
                marker.bindingIds().add(binding.id());
            }
        }
        if (cache.isEmpty() && markerSources != null && zone.bindings().stream().anyMatch(binding ->
                !UnstackableCategoryRule.ID.equals(binding.ruleId()))) {
            return Plan.failure("分类区没有可扫描的分类标记容器");
        }
        return new Plan(
                copyCache(cache),
                List.copyOf(byPosition.values()),
                ""
        );
    }

    private static RegionDefinition markerRegion(
            CategoryBindingDefinition binding
    ) {
        AreaBounds area = binding.markerArea();
        return new RegionDefinition(
                binding.id(),
                "category-marker",
                area.firstX(),
                area.firstY(),
                area.firstZ(),
                area.secondX(),
                area.secondY(),
                area.secondZ()
        );
    }

    private static Map<UUID, Map<BlockPosition, Set<String>>> copyCache(
            Map<UUID, Map<BlockPosition, Set<String>>> cache
    ) {
        Map<UUID, Map<BlockPosition, Set<String>>> result =
                new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<BlockPosition, Set<String>>> entry
                : cache.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return result;
    }

    record Target(
            ContainerTarget target,
            Set<UUID> bindingIds
    ) {
        Target(ContainerTarget target) {
            this(target, new LinkedHashSet<>());
        }
    }

    record Plan(
            Map<UUID, Map<BlockPosition, Set<String>>> cache,
            List<Target> targets,
            String error
    ) {
        static Plan failure(String message) {
            return new Plan(Map.of(), List.of(), message);
        }
    }
}
