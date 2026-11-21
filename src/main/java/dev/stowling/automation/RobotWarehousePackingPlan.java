package dev.stowling.automation;

import dev.stowling.v2.model.StoragePackingPlanner;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reuses warehouse boxes by physical capacity, regardless of their existing item categories. */
public final class RobotWarehousePackingPlan {
    private RobotWarehousePackingPlan() {
    }

    public static Optional<String> select(List<Box> boxes, Map<String, Integer> incoming,
            Map<String, Integer> stackSizes) {
        String selected = null;
        int mostFitting = 0;
        int mostStored = 0;
        for (Box box : boxes) {
            int fitting = ShulkerTransferPlanner.fitting(box.slots(),
                    StoragePackingPlanner.BOX_SLOTS, incoming, stackSizes).values().stream()
                    .mapToInt(Integer::intValue).sum();
            int stored = box.slots().stream().mapToInt(ShulkerTransferPlanner.Slot::count).sum();
            if (fitting > mostFitting || fitting > 0 && fitting == mostFitting
                    && stored > mostStored) {
                selected = box.key();
                mostFitting = fitting;
                mostStored = stored;
            }
        }
        return Optional.ofNullable(selected);
    }

    public record Box(String key, List<ShulkerTransferPlanner.Slot> slots) {
        public Box {
            slots = List.copyOf(slots);
        }
    }
}
