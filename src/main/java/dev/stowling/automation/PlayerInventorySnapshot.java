package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

public record PlayerInventorySnapshot(Map<String, Integer> itemCounts,
        Map<String, String> pickaxeIdentities, Map<String, String> persistentIdentities,
        List<PlayerInventorySlot> slots, int schemaVersion, Set<Integer> lockedSlots) {
    public static PlayerInventorySnapshot locks(Set<Integer> slots) {
        return new PlayerInventorySnapshot(Map.of(), Map.of(), Map.of(), List.of(), 2, slots);
    }

    public PlayerInventorySnapshot(Map<String, Integer> items, Map<String, String> pickaxes,
            Map<String, String> persistent) {
        this(items, pickaxes, persistent, List.of(), 1, Set.of());
    }

    public PlayerInventorySnapshot(Map<String, Integer> items, Map<String, String> pickaxes,
            Map<String, String> persistent, List<PlayerInventorySlot> slots) {
        this(items, pickaxes, persistent, slots, 1, Set.of());
    }
    public PlayerInventorySnapshot(Map<String, Integer> itemCounts) {
        this(itemCounts, Map.of());
    }

    public PlayerInventorySnapshot(Map<String, Integer> itemCounts,
            Map<String, String> pickaxeIdentities) {
        this(itemCounts, pickaxeIdentities, Map.of());
    }

    public PlayerInventorySnapshot {
        itemCounts = itemCounts == null ? Map.of() : itemCounts;
        slots = slots == null ? List.of() : List.copyOf(slots);
        lockedSlots = lockedSlots == null ? Set.of() : Set.copyOf(lockedSlots);
        if (lockedSlots.stream().anyMatch(index -> index < 0 || index >= 36)) {
            throw new IllegalArgumentException("锁定栏位必须在 0 到 35 之间");
        }
        if (!slots.isEmpty() && (slots.size() != 36
                || slots.stream().map(PlayerInventorySlot::index).distinct().count() != 36
                || slots.stream().anyMatch(slot -> slot.index() < 0 || slot.index() >= 36))) {
            throw new IllegalArgumentException("背包快照必须包含 36 个唯一栏位");
        }
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
        pickaxeIdentities = pickaxeIdentities == null
                ? Map.of() : Map.copyOf(pickaxeIdentities);
        persistentIdentities = persistentIdentities == null
                ? Map.of() : Map.copyOf(persistentIdentities);
        if (!itemCounts.keySet().containsAll(pickaxeIdentities.keySet())) {
            throw new IllegalArgumentException("pickaxe identities must belong to snapshot items");
        }
        if (!itemCounts.keySet().containsAll(persistentIdentities.keySet())) {
            throw new IllegalArgumentException(
                    "persistent identities must belong to snapshot items");
        }
    }

    public int count(String itemId) {
        return itemCounts.getOrDefault(itemId, 0);
    }

    public String persistentKey(String itemId) {
        return persistentIdentities.getOrDefault(itemId, itemId);
    }
}
