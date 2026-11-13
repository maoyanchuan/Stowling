package dev.stowling.routing;

import dev.stowling.domain.PackingTrigger;
import java.util.Objects;

public record PackingThreshold(PackingTrigger mode, int threshold) {
    public PackingThreshold {
        mode = Objects.requireNonNull(mode, "mode");
        if (mode == PackingTrigger.ON_OCCUPANCY && (threshold < 1 || threshold > 100)) {
            throw new IllegalArgumentException("occupancy threshold must be between 1 and 100");
        }
        if (mode == PackingTrigger.ON_ITEM_COUNT && threshold < 1) {
            throw new IllegalArgumentException("item threshold must be positive");
        }
    }

    public boolean reached(int occupiedSlots, int capacitySlots, int itemCount) {
        if (occupiedSlots < 0 || capacitySlots < 1 || occupiedSlots > capacitySlots) {
            throw new IllegalArgumentException("slot counts are invalid");
        }
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must not be negative");
        }
        if (mode == PackingTrigger.ON_ITEM_COUNT) {
            return itemCount >= threshold;
        }
        long scaledOccupied = (long) occupiedSlots * 100L;
        long scaledThreshold = (long) capacitySlots * threshold;
        return scaledOccupied >= scaledThreshold;
    }
}
