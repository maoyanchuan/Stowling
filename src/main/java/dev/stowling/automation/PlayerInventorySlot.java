package dev.stowling.automation;

import java.util.List;

public record PlayerInventorySlot(
        int index, String key, String icon, int count, int maxCount,
        List<String> tooltip, int remainingDurability, int maxDurability
) {
    public PlayerInventorySlot {
        tooltip = List.copyOf(tooltip);
    }
}
