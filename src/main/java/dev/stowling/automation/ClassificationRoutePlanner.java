package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Follows recognized layouts, with spatial routing available for unbound batches. */
public final class ClassificationRoutePlanner {
    private ClassificationRoutePlanner() {
    }

    public static List<ContainerTarget> fixedOrder(List<ClassificationLayoutEntry> layout,
            List<ContainerTarget> candidates) {
        Set<ContainerTarget> result = new LinkedHashSet<>();
        for (ClassificationLayoutEntry entry : layout) {
            entry.containers().stream().filter(candidates::contains).forEach(result::add);
        }
        result.addAll(candidates);
        return List.copyOf(result);
    }

    /** Keeps slice and marker order; capacity preferences only choose a marker's destination. */
    public static List<ContainerTarget> planLayout(
            List<ClassificationLayoutEntry> layout,
            List<ContainerTarget> candidates,
            Map<ContainerTarget, Set<String>> accepted,
            Set<String> items,
            Comparator<ContainerTarget> preference
    ) {
        Set<String> remaining = new LinkedHashSet<>(items);
        List<ContainerTarget> route = new ArrayList<>();
        for (ClassificationLayoutEntry entry : layout) {
            if (remaining.stream().noneMatch(key ->
                    ItemIdentity.matches(key, Set.of(entry.itemId())))) {
                continue;
            }
            ContainerTarget target = entry.containers().stream().filter(candidates::contains)
                    .min(preference).orElse(null);
            if (target == null) continue;
            if (!route.contains(target)) route.add(target);
            remaining.removeIf(key -> ItemIdentity.matches(key,
                    accepted.getOrDefault(target, Set.of())));
        }
        return List.copyOf(route);
    }

    public static List<ContainerTarget> plan(
            BlockPosition start,
            BlockPosition cache,
            List<ContainerTarget> candidates,
            Map<ContainerTarget, Set<String>> accepted,
            Set<String> items,
            Comparator<ContainerTarget> preference
    ) {
        // Keep the existing capacity preference when choosing between same-category boxes.
        Set<String> remaining = new LinkedHashSet<>(items.stream().sorted().toList());
        List<ContainerTarget> pending = new ArrayList<>();
        List<ContainerTarget> preferred = candidates.stream().sorted(preference).toList();
        for (ContainerTarget target : preferred) {
            Set<String> categories = accepted.getOrDefault(target, Set.of());
            if (remaining.removeIf(key -> ItemIdentity.matches(key, categories))) {
                pending.add(target);
            }
        }
        List<ContainerTarget> route = new ArrayList<>();
        BlockPosition cursor = start;
        while (!pending.isEmpty()) {
            BlockPosition from = cursor;
            ContainerTarget next = pending.stream().min(Comparator.comparingDouble(
                    target -> distance(from, target.position()))).orElseThrow();
            pending.remove(next);
            route.add(next);
            cursor = next.position();
        }
        // Bounded 2-opt removes crossings without an unbounded search on large warehouses.
        for (int pass = 0; pass < 8; pass++) {
            boolean improved = false;
            for (int first = 0; first < route.size() - 1; first++) {
                BlockPosition before = first == 0 ? start : route.get(first - 1).position();
                for (int last = first + 1; last < route.size(); last++) {
                    BlockPosition after = last + 1 == route.size()
                            ? cache : route.get(last + 1).position();
                    BlockPosition left = route.get(first).position();
                    BlockPosition right = route.get(last).position();
                    if (distance(before, right) + distance(left, after) + 0.000001
                            < distance(before, left) + distance(right, after)) {
                        Collections.reverse(route.subList(first, last + 1));
                        improved = true;
                    }
                }
            }
            if (!improved) {
                break;
            }
        }
        return List.copyOf(route);
    }

    private static double distance(BlockPosition first, BlockPosition second) {
        double x = (double) first.x() - second.x();
        double y = (double) first.y() - second.y();
        double z = (double) first.z() - second.z();
        return Math.sqrt(x * x + y * y + z * z);
    }
}
