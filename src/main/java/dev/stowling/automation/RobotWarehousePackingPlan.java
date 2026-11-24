package dev.stowling.automation;

import dev.stowling.v2.model.StoragePackingPlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reuses boxes by capacity while respecting the selected pure-box or mixed-box policy. */
public final class RobotWarehousePackingPlan {
    private RobotWarehousePackingPlan() {
    }

    public static Optional<String> select(List<Box> boxes, Map<String, Integer> incoming,
            Map<String, Integer> stackSizes) {
        return select(boxes, incoming, stackSizes, true);
    }

    public static Optional<String> select(List<Box> boxes, Map<String, Integer> incoming,
            Map<String, Integer> stackSizes, boolean mixedBoxes) {
        String selected = null;
        int mostFitting = 0;
        int mostStored = 0;
        for (Box box : boxes) {
            int fitting = fitting(box.slots(), StoragePackingPlanner.BOX_SLOTS,
                    incoming, stackSizes, mixedBoxes).values().stream()
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

    public static Map<String, Integer> fitting(List<ShulkerTransferPlanner.Slot> contents, int slots,
            Map<String, Integer> incoming, Map<String, Integer> stackSizes, boolean mixedBoxes) {
        if (mixedBoxes) return ShulkerTransferPlanner.fitting(contents, slots, incoming, stackSizes);
        var categories = contents.stream().filter(slot -> slot.count() > 0)
                .map(slot -> ItemIdentity.base(slot.key())).distinct().toList();
        if (categories.size() > 1) return Map.of();
        var choices = categories.isEmpty() ? incoming.keySet().stream()
                .filter(key -> !ItemIdentity.isBox(key)).map(ItemIdentity::base).distinct()
                .sorted().toList() : categories;
        Map<String, Integer> best = Map.of();
        int mostFitting = 0;
        for (String category : choices) {
            Map<String, Integer> matching = new LinkedHashMap<>();
            incoming.forEach((key, count) -> {
                if (category.equals(ItemIdentity.base(key))) matching.put(key, count);
            });
            var fitting = ShulkerTransferPlanner.fitting(contents, slots, matching, stackSizes);
            int count = fitting.values().stream().mapToInt(Integer::intValue).sum();
            if (count > mostFitting) {
                best = fitting;
                mostFitting = count;
            }
        }
        return best;
    }

    public record Box(String key, List<ShulkerTransferPlanner.Slot> slots) {
        public Box {
            slots = List.copyOf(slots);
        }
    }
}
