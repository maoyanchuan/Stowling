package dev.stowling.v2.category;

import dev.stowling.automation.ClassificationLayoutEntry;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryItemBinding;
import dev.stowling.v2.model.CategoryZoneOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves a complete zone snapshot before appending scoped item bindings. */
public final class CategoryZoneClassifier {
    public record Scan(
            CategoryBindingDefinition binding,
            CategoryZoneOptions options,
            UUID scopeId,
            List<CategoryMarker> markers,
            List<ContainerTarget> containers
    ) {
        public Scan {
            markers = List.copyOf(markers);
            containers = List.copyOf(containers);
        }

        private Scan withMarkers(List<CategoryMarker> values) {
            return new Scan(binding, options, scopeId, values, containers);
        }
    }

    private CategoryZoneClassifier() {
    }

    public static CategoryRuleResult resolve(
            List<Scan> raw,
            CategoryRuleRegistry rules,
            Set<CategoryMarkerSource> explicitSources
    ) {
        List<Scan> filtered = raw.stream().map(scan -> filter(scan, rules, explicitSources)).toList();
        // References are collected once, before any inference, across the entire zone.
        Set<String> references = new LinkedHashSet<>();
        for (Scan scan : filtered) {
            if (!DirectBlockIdCategoryRule.ID.equals(scan.binding().ruleId())) continue;
            for (List<CategoryMarker> column : columns(scan).values()) {
                Set<String> ids = ids(column);
                if (ids.size() == 1 && column.stream().allMatch(marker ->
                        marker.source() == CategoryMarkerSource.PLACED_ITEM)) {
                    references.addAll(ids);
                }
            }
        }
        List<Scan> resolvedScans = filtered.stream().map(scan -> reduce(scan, references)).toList();
        Map<String, List<ContainerTarget>> categories = new LinkedHashMap<>();
        List<ClassificationLayoutEntry> layout = new ArrayList<>();
        for (Scan scan : resolvedScans) {
            CategoryRuleResult result = resolveAutomatic(scan, rules, explicitSources);
            if (!result.succeeded()) {
                return CategoryRuleResult.failure("识别起点 "
                        + new BlockPosition(scan.binding().markerArea().firstX(),
                                scan.binding().markerArea().firstY(),
                                scan.binding().markerArea().firstZ()) + "：" + result.message());
            }
            result.categories().forEach((id, targets) -> append(categories, id, targets));
            layout.addAll(result.layout());
        }
        Map<UUID, List<Scan>> scopes = new LinkedHashMap<>();
        for (Scan scan : resolvedScans) {
            scopes.computeIfAbsent(scan.scopeId(), ignored -> new ArrayList<>()).add(scan);
        }
        for (List<Scan> scope : scopes.values()) {
            CategoryZoneOptions options = scope.getFirst().options();
            if (options == null || !options.multiItemBindingsEnabled()) continue;
            options.validateActiveRules();
            for (CategoryItemBinding rule : options.multiItemBindings()) {
                List<ContainerTarget> targets = bindingTargets(scope, rule);
                if (targets.isEmpty()) {
                    return CategoryRuleResult.failure("物品绑定「" + rule.name()
                            + "」没有匹配到有效标记或储存容器，请检查目标与识别排除设置");
                }
                for (String id : rule.itemIdList()) {
                    append(categories, id, targets);
                    layout.add(new ClassificationLayoutEntry(id, targets));
                }
            }
        }
        return CategoryRuleResult.success(categories, layout);
    }

    private static Scan filter(
            Scan scan, CategoryRuleRegistry rules, Set<CategoryMarkerSource> explicitSources
    ) {
        CategoryZoneOptions options = scan.options();
        Set<BlockPosition> excluded = options != null && options.ignoreMarkerPositions()
                ? Set.copyOf(options.excludedMarkerPositions()) : Set.of();
        Set<String> ignored = Set.copyOf(scan.binding().ignoredMarkerBlockIds());
        CategoryRule rule = rules.require(scan.binding().ruleId());
        return scan.withMarkers(scan.markers().stream()
                .filter(marker -> !excluded.contains(marker.position()))
                .filter(marker -> marker.source() != CategoryMarkerSource.PLACED_ITEM
                        || !ignored.contains(marker.itemId()))
                .filter(marker -> explicitSources != null || rule.accepts(marker))
                .toList());
    }

    private static Map<List<ContainerTarget>, List<CategoryMarker>> columns(Scan scan) {
        Map<List<ContainerTarget>, List<CategoryMarker>> columns = new LinkedHashMap<>();
        for (CategoryMarker marker : scan.markers()) {
            if (marker.source() == CategoryMarkerSource.CONTAINER_CONTENTS) continue;
            List<ContainerTarget> targets = targets(scan, marker);
            if (!targets.isEmpty()) {
                columns.computeIfAbsent(targets, ignored -> new ArrayList<>()).add(marker);
            }
        }
        return columns;
    }

    private static Set<String> ids(List<CategoryMarker> markers) {
        Set<String> ids = new LinkedHashSet<>();
        markers.forEach(marker -> ids.add(marker.itemId()));
        return ids;
    }

    private static Scan reduce(Scan scan, Set<String> references) {
        if (scan.options() == null || !scan.options().ignoreRepeatedBlocks()
                || !DirectBlockIdCategoryRule.ID.equals(scan.binding().ruleId())) return scan;
        Set<CategoryMarker> removed = new LinkedHashSet<>();
        for (List<CategoryMarker> column : columns(scan).values()) {
            if (ids(column).size() <= 1) continue;
            List<CategoryMarker> remaining = column.stream().filter(marker ->
                    marker.source() != CategoryMarkerSource.PLACED_ITEM
                            || !references.contains(marker.itemId())).toList();
            if (ids(remaining).size() == 1) {
                column.stream().filter(marker -> !remaining.contains(marker)).forEach(removed::add);
            }
        }
        return scan.withMarkers(scan.markers().stream()
                .filter(marker -> !removed.contains(marker)).toList());
    }

    private static CategoryRuleResult resolveAutomatic(
            Scan scan, CategoryRuleRegistry rules, Set<CategoryMarkerSource> explicitSources
    ) {
        CategoryBindingDefinition binding = scan.binding();
        if (UnstackableCategoryRule.ID.equals(binding.ruleId())) {
            if (binding.markerArea().contains(binding.storageArea())
                    && binding.storageArea().contains(binding.markerArea())) {
                return rules.require(binding.ruleId()).resolve(scan.markers(), scan.containers());
            }
            return UnstackableCategoryRule.resolveMarkerArea(scan.markers(), scan.containers());
        }
        if (explicitSources != null) {
            boolean rejectConflicts = DirectBlockIdCategoryRule.ID.equals(binding.ruleId())
                    && scan.options() != null && scan.options().ignoreRepeatedBlocks();
            return ColumnCategoryMatcher.resolve(scan.markers(), scan.containers(),
                    binding.containerOverrides(), rejectConflicts, binding.wholeStorage());
        }
        CategoryRule rule = rules.require(binding.ruleId());
        if (rule instanceof ContainerContentsCategoryRule contentsRule) {
            return contentsRule.resolve(scan.markers(), scan.containers(),
                    binding.containerOverrides(), binding.wholeStorage());
        }
        return rule.resolve(scan.markers(), scan.containers(), binding.containerOverrides());
    }

    private static List<ContainerTarget> targets(Scan scan, CategoryMarker marker) {
        return ColumnCategoryMatcher.targets(marker, scan.containers(),
                scan.binding().containerOverrides(), scan.binding().wholeStorage());
    }

    private static List<ContainerTarget> bindingTargets(List<Scan> scope, CategoryItemBinding rule) {
        Set<ContainerTarget> targets = new LinkedHashSet<>();
        for (Scan scan : scope) {
            if ("container".equals(rule.targetType())) {
                // Absolute coordinates are never shifted or broadcast by slice expansion.
                BlockPosition position = rule.position().blockPosition();
                var area = scan.binding().storageArea();
                if (position.x() < area.minX() || position.x() > area.maxX()
                        || position.y() < area.minY() || position.y() > area.maxY()
                        || position.z() < area.minZ() || position.z() > area.maxZ()) continue;
                scan.containers().stream().filter(target ->
                        target.entrances().contains(position))
                        .forEach(targets::add);
                continue;
            }
            for (CategoryMarker marker : scan.markers()) {
                boolean matches = "marker-id".equals(rule.targetType())
                        ? marker.itemId().equals(rule.markerId().trim())
                        : marker.position().equals(rule.position().blockPosition());
                if (matches) targets.addAll(targets(scan, marker));
            }
        }
        return List.copyOf(targets);
    }

    private static void append(
            Map<String, List<ContainerTarget>> categories, String id, List<ContainerTarget> incoming
    ) {
        Set<ContainerTarget> merged = new LinkedHashSet<>(categories.getOrDefault(id, List.of()));
        merged.addAll(incoming);
        categories.put(id, List.copyOf(merged));
    }
}
