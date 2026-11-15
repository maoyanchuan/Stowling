package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerScanResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class TaskRunWorkspace {
    private final Map<String, Integer> collectedItems = new LinkedHashMap<>();
    private ContainerScanResult containerScan;

    public synchronized void recordCollected(String itemId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        collectedItems.merge(itemId, count, Math::addExact);
    }

    public synchronized Map<String, Integer> collectedItems() {
        return Map.copyOf(collectedItems);
    }

    public synchronized void removeCollected(String itemId, int count) {
        int current = collectedItems.getOrDefault(itemId, 0);
        int remaining = Math.max(0, current - count);
        if (remaining == 0) {
            collectedItems.remove(itemId);
        } else {
            collectedItems.put(itemId, remaining);
        }
    }

    public synchronized void setContainerScan(ContainerScanResult scan) {
        containerScan = scan;
    }

    public synchronized Optional<ContainerScanResult> containerScan() {
        return Optional.ofNullable(containerScan);
    }
}
