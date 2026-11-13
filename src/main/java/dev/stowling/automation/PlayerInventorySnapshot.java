package dev.stowling.automation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PlayerInventorySnapshot(int totalSlots, List<PlayerInventorySlot> slots) {
    public PlayerInventorySnapshot {
        if (totalSlots < 1) {
            throw new IllegalArgumentException("totalSlots must be positive");
        }
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        Set<Integer> seenSlots = new HashSet<>();
        for (PlayerInventorySlot slot : slots) {
            if (slot.slot() >= totalSlots) {
                throw new IllegalArgumentException("slot index exceeds inventory size");
            }
            if (!seenSlots.add(slot.slot())) {
                throw new IllegalArgumentException("inventory slot is duplicated");
            }
        }
    }

    public int freeSlots() {
        return totalSlots - slots.size();
    }

    public Map<String, Integer> itemCounts() {
        Map<String, Integer> result = new HashMap<>();
        for (PlayerInventorySlot slot : slots) {
            result.merge(slot.itemId(), slot.count(), Integer::sum);
        }
        return Map.copyOf(result);
    }
}
