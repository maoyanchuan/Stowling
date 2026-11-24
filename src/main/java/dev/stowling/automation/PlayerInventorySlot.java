package dev.stowling.automation;

import java.util.List;

public record PlayerInventorySlot(
        int index, String key, String icon, int count, int maxCount,
        List<String> tooltip, int remainingDurability, int maxDurability
) {
    public static PlayerInventorySlot empty(int index) {
        return new PlayerInventorySlot(index, "", "", 0, 64, List.of(), 0, 0);
    }

    public PlayerInventorySlot {
        tooltip = List.copyOf(tooltip);
    }
}
