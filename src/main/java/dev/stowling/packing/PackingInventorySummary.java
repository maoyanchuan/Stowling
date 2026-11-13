package dev.stowling.packing;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record PackingInventorySummary(
        Map<String, Long> looseItems,
        Map<String, Long> packedItems,
        List<UnknownInventoryContent> unknownContents
) {
    public PackingInventorySummary {
        looseItems = positiveTotals(looseItems, "looseItems");
        packedItems = positiveTotals(packedItems, "packedItems");
        unknownContents = List.copyOf(
                Objects.requireNonNull(unknownContents, "unknownContents")
        );
    }

    public long looseQuantity(String itemId) {
        return quantity(looseItems, itemId);
    }

    public long packedQuantity(String itemId) {
        return quantity(packedItems, itemId);
    }

    public boolean hasUnknownContents() {
        return !unknownContents.isEmpty();
    }

    private static Map<String, Long> positiveTotals(
            Map<String, Long> source,
            String fieldName
    ) {
        Objects.requireNonNull(source, fieldName);
        TreeMap<String, Long> totals = new TreeMap<>();
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            String itemId = PackingChecks.requireText(entry.getKey(), "item id");
            long count = Objects.requireNonNull(entry.getValue(), "item count");
            if (count < 1) {
                throw new IllegalArgumentException("inventory totals must be positive");
            }
            totals.put(itemId, count);
        }
        return Collections.unmodifiableMap(totals);
    }

    private static long quantity(Map<String, Long> totals, String itemId) {
        String checkedItemId = PackingChecks.requireText(itemId, "itemId");
        return totals.getOrDefault(checkedItemId, 0L);
    }
}
