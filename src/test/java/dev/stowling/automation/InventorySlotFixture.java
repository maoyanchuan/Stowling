package dev.stowling.automation;

import dev.stowling.v2.runtime.modules.PlayerInventoryManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InventorySlotFixture {
    private final Map<String, List<Integer>> positions = new LinkedHashMap<>();

    public PlayerInventorySnapshot snapshot(Map<String, Integer> items) {
        List<PlayerInventorySlot> slots = new ArrayList<>();
        for (int index = 0; index < 36; index++) slots.add(PlayerInventorySlot.empty(index));
        positions.keySet().removeIf(key -> !items.containsKey(key));
        java.util.Set<Integer> occupied = new java.util.HashSet<>();
        positions.values().forEach(occupied::addAll);
        items.forEach((key, count) -> {
            String base = ItemIdentity.base(key);
            List<Integer> assigned = positions.computeIfAbsent(key, ignored -> new ArrayList<>());
            int remaining = count;
            int stack = 0;
            while (remaining > 0) {
                if (assigned.size() <= stack) {
                    int free = java.util.stream.IntStream.range(0, 36)
                            .filter(index -> !occupied.contains(index)).findFirst().orElseThrow();
                    assigned.add(free);
                    occupied.add(free);
                }
                int index = assigned.get(stack++);
                int amount = Math.min(64, remaining);
                slots.set(index, new PlayerInventorySlot(index, key, base, amount, 64,
                        List.of(), 0, 0));
                remaining -= amount;
            }
        });
        return new PlayerInventorySnapshot(items, Map.of(), Map.of(), slots);
    }

    public static void lockFirst(PlayerInventoryManager manager, String revision, String key,
            boolean locked) {
        int index = manager.current().orElseThrow().slots().stream()
                .filter(slot -> slot.key().equals(key)).findFirst().orElseThrow().index();
        manager.setLocked(revision, index, locked);
    }
}
