package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerScanResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TaskRunWorkspace {
    private final Map<String, Integer> collectedItems = new LinkedHashMap<>();
    private final Map<UUID, Integer> cacheUpdates = new LinkedHashMap<>();
    private ContainerScanResult containerScan;
    private UUID containerScanRegionId;
    private ContainerCategoryResult containerCategories;

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
        setContainerScan(null, scan);
    }

    public synchronized void setContainerScan(
            UUID regionId,
            ContainerScanResult scan
    ) {
        containerScanRegionId = regionId;
        containerScan = scan;
        containerCategories = null;
    }

    public synchronized Optional<ContainerScanResult> containerScan() {
        return Optional.ofNullable(containerScan);
    }

    public synchronized Optional<UUID> containerScanRegionId() {
        return Optional.ofNullable(containerScanRegionId);
    }

    public synchronized void recordCacheUpdate(
            UUID regionId,
            int itemCount
    ) {
        if (regionId == null || itemCount <= 0) {
            return;
        }
        cacheUpdates.merge(regionId, itemCount, Math::addExact);
    }

    public synchronized Map<UUID, Integer> consumeCacheUpdates() {
        Map<UUID, Integer> result = Map.copyOf(cacheUpdates);
        cacheUpdates.clear();
        return result;
    }

    public synchronized void setContainerCategories(
            ContainerCategoryResult categories
    ) {
        containerCategories = categories;
    }

    public synchronized Optional<ContainerCategoryResult> containerCategories() {
        return Optional.ofNullable(containerCategories);
    }
}
