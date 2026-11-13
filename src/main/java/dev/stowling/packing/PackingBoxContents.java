package dev.stowling.packing;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record PackingBoxContents(
        Map<String, Integer> itemCounts,
        int occupiedSlots,
        int capacitySlots
) {
    public PackingBoxContents {
        Objects.requireNonNull(itemCounts, "itemCounts");
        TreeMap<String, Integer> normalized = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String itemId = PackingChecks.requireText(entry.getKey(), "item id");
            int count = Objects.requireNonNull(entry.getValue(), "item count");
            if (count < 0) {
                throw new IllegalArgumentException("item count must not be negative");
            }
            if (count > 0) {
                normalized.put(itemId, count);
            }
        }
        if (capacitySlots < 1) {
            throw new IllegalArgumentException("capacitySlots must be positive");
        }
        if (occupiedSlots < 0 || occupiedSlots > capacitySlots) {
            throw new IllegalArgumentException(
                    "occupiedSlots must be between zero and capacitySlots"
            );
        }
        if (normalized.isEmpty() != (occupiedSlots == 0)) {
            throw new IllegalArgumentException(
                    "occupiedSlots must be zero exactly when the box is empty"
            );
        }
        if (occupiedSlots < normalized.size()) {
            throw new IllegalArgumentException(
                    "occupiedSlots cannot be smaller than the number of item ids"
            );
        }
        itemCounts = Collections.unmodifiableMap(normalized);
    }

    public static PackingBoxContents empty(int capacitySlots) {
        return new PackingBoxContents(Map.of(), 0, capacitySlots);
    }

    public static PackingBoxContents exact(
            String itemId,
            int count,
            int occupiedSlots,
            int capacitySlots
    ) {
        String checkedItemId = PackingChecks.requireText(itemId, "item id");
        return new PackingBoxContents(
                Map.of(checkedItemId, count),
                occupiedSlots,
                capacitySlots
        );
    }

    public long totalCount() {
        long total = 0L;
        for (int count : itemCounts.values()) {
            total = Math.addExact(total, count);
        }
        return total;
    }

    public long exactItemCount(String itemId) {
        String checkedItemId = PackingChecks.requireText(itemId, "item id");
        return itemCounts.getOrDefault(checkedItemId, 0);
    }

    public boolean containsOnly(String itemId) {
        String checkedItemId = PackingChecks.requireText(itemId, "item id");
        return itemCounts.keySet().stream().allMatch(checkedItemId::equals);
    }

    public boolean occupiedRatioAtLeast(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be between 0 and 100");
        }
        long scaledOccupied = (long) occupiedSlots * 100L;
        long scaledCapacity = (long) capacitySlots * percentage;
        return scaledOccupied >= scaledCapacity;
    }
}
