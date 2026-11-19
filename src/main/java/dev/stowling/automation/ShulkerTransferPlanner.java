package dev.stowling.automation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plans exact-component transfers using physical slots and each item's actual stack limit. */
public final class ShulkerTransferPlanner {
    private ShulkerTransferPlanner() {
    }

    public record Slot(String key, int count, int stackSize) {
    }

    public static Map<String, Integer> fitting(List<Slot> contents, int slots,
            Map<String, Integer> incoming, Map<String, Integer> stackSizes) {
        int empty = Math.max(0, slots - (int) contents.stream()
                .filter(slot -> slot.count() > 0).count());
        Map<String, Integer> slack = new LinkedHashMap<>();
        contents.stream().filter(slot -> slot.count() > 0).forEach(slot ->
                slack.merge(slot.key(), Math.max(0, slot.stackSize() - slot.count()), Math::addExact));
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : incoming.entrySet()) {
            String key = entry.getKey();
            if (ItemIdentity.isBox(key)) continue;
            int limit = Math.max(1, stackSizes.getOrDefault(key, 64));
            int matching = slack.getOrDefault(key, 0);
            int amount = (int) Math.min(Math.max(0, entry.getValue()),
                    (long) matching + (long) empty * limit);
            if (amount == 0) continue;
            int newItems = Math.max(0, amount - matching);
            int newSlots = (int) ((newItems + (long) limit - 1) / limit);
            empty -= newSlots;
            slack.put(key, matching + newSlots * limit - amount);
            result.put(key, amount);
        }
        return Collections.unmodifiableMap(result);
    }

    public static int remainingBatchSlots(Map<String, Integer> owned,
            Map<String, Integer> stackSizes, int capacity) {
        long used = 0;
        for (var entry : owned.entrySet()) {
            if (ItemIdentity.isEmptyBox(entry.getKey())) continue;
            int limit = ItemIdentity.isBox(entry.getKey()) ? 1
                    : Math.max(1, stackSizes.getOrDefault(entry.getKey(), 64));
            used += (entry.getValue() + (long) limit - 1) / limit;
        }
        return (int) Math.max(0, capacity - used);
    }
}
