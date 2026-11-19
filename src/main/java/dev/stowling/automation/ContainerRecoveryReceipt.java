package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A recovery acknowledgement survives replacement of the action during interruption cleanup. */
public final class ContainerRecoveryReceipt {
    private boolean confirmed;
    private Map<String, Integer> before;
    private Set<String> compatibleBefore = Set.of();
    private Set<UUID> existingDrops = Set.of();
    private UUID trackedDrop;
    private int matchingBefore;

    public void begin(Map<String, Integer> inventory, Set<String> compatibleKeys,
            Set<UUID> nearbyDrops) {
        if (before != null) return;
        before = new LinkedHashMap<>(inventory);
        compatibleBefore = Set.copyOf(compatibleKeys);
        existingDrops = Set.copyOf(nearbyDrops);
        matchingBefore = compatibleKeys.stream().mapToInt(key ->
                inventory.getOrDefault(key, 0)).sum();
    }

    public boolean begun() {
        return before != null;
    }

    public Optional<String> acquiredKey(Map<String, Integer> inventory, Set<String> compatibleKeys) {
        if (confirmed || before == null || compatibleKeys.stream().mapToInt(key ->
                inventory.getOrDefault(key, 0)).sum() <= matchingBefore) {
            return Optional.empty();
        }
        return compatibleKeys.stream().filter(key ->
                inventory.getOrDefault(key, 0) > before.getOrDefault(key, 0)).findFirst();
    }

    public boolean acceptsDrop(UUID id) {
        return before != null && !existingDrops.contains(id)
                && (trackedDrop == null || trackedDrop.equals(id));
    }

    public void trackDrop(UUID id) {
        if (acceptsDrop(id)) trackedDrop = id;
    }

    public boolean tracksDrop(UUID id) {
        return id.equals(trackedDrop);
    }

    public void recordRemoval(String key, int count) {
        if (before == null) return;
        int removed = Math.min(Math.max(0, count), before.getOrDefault(key, 0));
        before.computeIfPresent(key, (ignored, value) -> value - removed);
        if (compatibleBefore.contains(key)) matchingBefore -= removed;
    }

    public boolean confirmed() {
        return confirmed;
    }

    public void confirm(Runnable recordAcquired) {
        if (!confirmed) {
            recordAcquired.run();
            confirmed = true;
        }
    }
}
