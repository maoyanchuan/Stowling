package dev.stowling.v2.control;

import dev.stowling.automation.PlayerInventorySlot;
import java.util.List;

public record PlayerInventoryResponse(
        String revision, boolean available, boolean busy, boolean classificationAvailable,
        List<Item> items, List<PlayerInventorySlot> slots, java.util.Set<Integer> lockedSlots
) {
    public PlayerInventoryResponse {
        items = List.copyOf(items);
        slots = List.copyOf(slots);
        lockedSlots = java.util.Set.copyOf(lockedSlots);
    }

    public record Item(String key, String itemId, String name, int count,
            int protectedCount, int taskCount, int classificationCount) {
    }
}
