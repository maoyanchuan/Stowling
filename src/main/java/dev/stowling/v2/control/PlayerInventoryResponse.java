package dev.stowling.v2.control;

import dev.stowling.automation.PlayerInventorySlot;
import java.util.List;

public record PlayerInventoryResponse(
        String revision, boolean available, boolean busy, boolean classificationAvailable,
        List<Item> items, List<PlayerInventorySlot> slots
) {
    public PlayerInventoryResponse {
        items = List.copyOf(items);
        slots = List.copyOf(slots);
    }

    public record Item(String key, String itemId, String name, int count,
            int protectedCount, int taskCount, int classificationCount) {
    }
}
