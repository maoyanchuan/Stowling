package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PlayerInventorySnapshot(Map<String, Integer> itemCounts,
        Map<String, String> pickaxeIdentities) {
    public PlayerInventorySnapshot(Map<String, Integer> itemCounts) {
        this(itemCounts, Map.of());
    }

    public PlayerInventorySnapshot {
        Objects.requireNonNull(itemCounts, "itemCounts");
        Map<String, Integer> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String itemId = entry.getKey() == null
                    ? ""
                    : entry.getKey().trim();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (itemId.isEmpty() || count <= 0) {
                throw new IllegalArgumentException(
                        "inventory item IDs and counts must be positive"
                );
            }
            checked.merge(itemId, count, Math::addExact);
        }
        itemCounts = Map.copyOf(checked);
        pickaxeIdentities = Map.copyOf(pickaxeIdentities);
        if (!itemCounts.keySet().containsAll(pickaxeIdentities.keySet())) {
            throw new IllegalArgumentException("pickaxe identities must belong to snapshot items");
        }
    }

    public int count(String itemId) {
        return itemCounts.getOrDefault(itemId, 0);
    }
}
