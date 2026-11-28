package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-major shortcut only when the entire loose payload fits the existing delivery rules. */
public final class ClassificationLooseBatchPlan {
    private ClassificationLooseBatchPlan() {
    }

    public static Map<ContainerTarget, Map<String, Integer>> plan(
            Map<ContainerTarget, Map<String, Integer>> sources, Map<String, Integer> looseLimits,
            List<ShulkerTransferPlanner.Slot> inventory, int inventorySlots,
            Map<String, Integer> stackSizes, int stackLimit) {
        Map<String, Integer> total = new LinkedHashMap<>();
        sources.values().forEach(items -> items.forEach((key, count) ->
                total.merge(key, count, Math::addExact)));
        if (total.isEmpty() || total.keySet().stream().anyMatch(ItemIdentity::isBox)) {
            return Map.of();
        }
        Map<String, Integer> categories = new LinkedHashMap<>();
        total.forEach((key, count) -> categories.merge(ItemIdentity.category(key), count,
                Math::addExact));
        if (categories.entrySet().stream().anyMatch(entry -> entry.getValue()
                > looseLimits.getOrDefault(entry.getKey(), 0))) return Map.of();
        long stacks = total.entrySet().stream().mapToLong(entry -> {
            int size = Math.max(1, stackSizes.getOrDefault(entry.getKey(), 64));
            return ((long) entry.getValue() + size - 1) / size;
        }).sum();
        if (stacks > stackLimit || !total.equals(ShulkerTransferPlanner.fittingInventory(
                inventory, inventorySlots, total, stackSizes))) return Map.of();
        Map<ContainerTarget, Map<String, Integer>> result = new LinkedHashMap<>();
        sources.forEach((target, items) -> {
            if (!items.isEmpty()) result.put(target, Map.copyOf(items));
        });
        return java.util.Collections.unmodifiableMap(result);
    }
}
