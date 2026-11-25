package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class EmptyBoxSupplyPlan {
    private EmptyBoxSupplyPlan() {
    }

    public static int emptyCount(Map<String, Integer> contents) {
        return contents.entrySet().stream().filter(entry -> ItemIdentity.isEmptyBox(entry.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
    }

    /** Plans only real surplus visits from already recorded source inventories. */
    public static Map<ContainerTarget, Integer> cachedSurplus(List<ContainerTarget> sources,
            java.util.function.Function<ContainerTarget, Map<String, Integer>> contents,
            int retainedLimit) {
        Map<ContainerTarget, Integer> result = new LinkedHashMap<>();
        int remaining = Math.max(0, retainedLimit);
        for (ContainerTarget source : sources(sources, null, List.of())) {
            int count = emptyCount(contents.apply(source));
            int keep = Math.min(remaining, count);
            remaining -= keep;
            if (count > keep) result.put(source, keep);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public static Map<String, Integer> returnBatch(Map<String, Integer> owned, int maximum) {
        return surplus(owned, 0, maximum);
    }

    public static List<ContainerTarget> classificationReturns(List<ContainerTarget> caches,
            List<ContainerTarget> warehouse, boolean enabled, int cachedCount, int retainedLimit) {
        List<ContainerTarget> candidates = new ArrayList<>();
        if (!enabled || cachedCount < retainedLimit) candidates.addAll(caches);
        if (enabled) candidates.addAll(warehouse);
        return sources(candidates, null, List.of());
    }

    public static Map<String, Integer> surplus(Map<String, Integer> contents,
            int retained, int capacity) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int keep = Math.max(0, retained);
        int room = Math.max(0, capacity);
        for (var entry : contents.entrySet()) {
            if (!ItemIdentity.isEmptyBox(entry.getKey())) continue;
            int reserve = Math.min(keep, entry.getValue());
            keep -= reserve;
            int move = Math.min(room, entry.getValue() - reserve);
            if (move > 0) result.put(entry.getKey(), move);
            room -= move;
        }
        return Map.copyOf(result);
    }

    public static boolean canReturnUnusedBoxes(java.util.Map<String, Integer> owned,
            Set<String> emptyBoxKeys) {
        return owned.entrySet().stream().noneMatch(entry -> entry.getValue() > 0
                && !emptyBoxKeys.contains(entry.getKey()));
    }

    public static List<ContainerTarget> returns(ContainerTarget preferred,
            List<ContainerTarget> sources, List<ContainerTarget> fallback,
            Set<BlockPosition> storagePositions, Set<BlockPosition> partialBoxPositions) {
        List<ContainerTarget> candidates = new ArrayList<>();
        if (preferred != null) candidates.add(preferred);
        candidates.addAll(sources);
        candidates.addAll(fallback);
        return sources(candidates.stream().filter(target ->
                target.entrances().stream().noneMatch(position -> storagePositions.contains(position)
                        || partialBoxPositions.contains(position))).toList(),
                null, List.of());
    }

    public static List<ContainerTarget> sources(List<ContainerTarget> caches,
            ContainerTarget selected, List<BlockPosition> facilities) {
        List<ContainerTarget> candidates = new ArrayList<>();
        if (selected != null) {
            caches.stream().filter(target -> target.entrances().contains(selected.position()))
                    .findFirst().ifPresent(candidates::add);
        }
        candidates.addAll(caches);
        facilities.forEach(position -> candidates.add(new ContainerTarget(position, "container")));
        Set<BlockPosition> seen = new LinkedHashSet<>();
        List<ContainerTarget> result = new ArrayList<>();
        for (ContainerTarget target : candidates) {
            if (target.entrances().stream().noneMatch(seen::contains)) {
                result.add(target);
                seen.addAll(target.entrances());
            }
        }
        return List.copyOf(result);
    }
}
