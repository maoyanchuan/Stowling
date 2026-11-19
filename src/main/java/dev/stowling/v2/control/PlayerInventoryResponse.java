package dev.stowling.v2.control;

import java.util.List;

public record PlayerInventoryResponse(
        String revision, boolean available, boolean busy, boolean classificationAvailable,
        List<Item> items
) {
    public PlayerInventoryResponse {
        items = List.copyOf(items);
    }

    public record Item(String key, String itemId, String name, int count,
            int protectedCount, int taskCount, int classificationCount) {
    }
}
