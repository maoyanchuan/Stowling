package dev.stowling.v2.runtime.modules;

import dev.stowling.domain.BlockPosition;
import java.util.LinkedHashMap;
import java.util.Map;

/** Confirmed deliveries for one logical job, retained across urgent-task preemption. */
public final class TaskProgress {
    private final Map<BlockPosition, Map<String, Integer>> deliveries = new LinkedHashMap<>();

    public synchronized void recordDelivery(BlockPosition position, Map<String, Integer> moved) {
        Map<String, Integer> items = deliveries.computeIfAbsent(position,
                ignored -> new LinkedHashMap<>());
        moved.forEach((key, count) -> {
            if (count > 0) items.merge(key, count, Math::addExact);
        });
    }

    public synchronized Map<String, Integer> deliveredTo(BlockPosition position) {
        return Map.copyOf(deliveries.getOrDefault(position, Map.of()));
    }

    public synchronized Map<String, Integer> remainingAt(BlockPosition position,
            Map<String, Integer> requested) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, Integer> delivered = deliveredTo(position);
        requested.forEach((key, count) -> {
            int needed = Math.max(0, count - delivered.getOrDefault(key, 0));
            if (needed > 0) remaining.put(key, needed);
        });
        return Map.copyOf(remaining);
    }
}
