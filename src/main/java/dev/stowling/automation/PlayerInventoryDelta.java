package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PlayerInventoryDelta(
        Map<String, Integer> added,
        Map<String, Integer> removed
) {
    public PlayerInventoryDelta {
        added = checked(added);
        removed = checked(removed);
    }

    public static PlayerInventoryDelta between(
            PlayerInventorySnapshot previous,
            PlayerInventorySnapshot current
    ) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Map<String, Integer> added = new LinkedHashMap<>();
        Map<String, Integer> removed = new LinkedHashMap<>();
        Set<String> itemIds = new LinkedHashSet<>(
                previous.itemCounts().keySet()
        );
        itemIds.addAll(current.itemCounts().keySet());
        for (String itemId : itemIds) {
            int difference = current.count(itemId) - previous.count(itemId);
            if (difference > 0) {
                added.put(itemId, difference);
            } else if (difference < 0) {
                removed.put(itemId, Math.negateExact(difference));
            }
        }
        return new PlayerInventoryDelta(added, removed);
    }

    public boolean changed() {
        return !added.isEmpty() || !removed.isEmpty();
    }

    private static Map<String, Integer> checked(
            Map<String, Integer> values
    ) {
        Objects.requireNonNull(values, "inventory delta");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            String itemId = entry.getKey() == null
                    ? ""
                    : entry.getKey().trim();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (itemId.isEmpty() || count <= 0) {
                throw new IllegalArgumentException(
                        "inventory delta IDs and counts must be positive"
                );
            }
            result.merge(itemId, count, Math::addExact);
        }
        return Map.copyOf(result);
    }
}
