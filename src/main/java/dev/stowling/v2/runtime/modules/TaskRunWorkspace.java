package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ClassificationRoutePlanner;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.domain.BlockPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class TaskRunWorkspace {
    private final PlayerInventoryAccount inventoryAccount;
    private final Map<UUID, Integer> cacheUpdates = new LinkedHashMap<>();
    private final Map<ContainerTarget, Set<String>> acceptedItemsByContainer =
            new LinkedHashMap<>();
    private final List<ContainerTarget> targetContainers = new ArrayList<>();
    private final List<ContainerTarget> containerCandidates = new ArrayList<>();
    private final List<ContainerTarget> cacheContainers = new ArrayList<>();
    private final Set<ContainerTarget> unavailableTargets =
            new LinkedHashSet<>();
    private Set<String> classifiedItemIds = Set.of();
    private final List<MarkerContainerScanAction.Target> markerTargets =
            new ArrayList<>();
    private ContainerScanResult containerScan;
    private Map<BlockPosition, Map<String, Integer>> cacheInventory = Map.of();
    private UUID containerScanRegionId;
    private PlayerInventorySnapshot initialInventory;
    private PlayerInventorySnapshot lastInventory;
    private Map<UUID, Map<BlockPosition, Set<String>>> markerCache = Map.of();
    private ContainerTarget selectedContainer;
    private ContainerTarget selectedStorageTarget;
    private ContainerTarget selectedMarkerContainer;
    private boolean storageUsesCacheFallback;
    private int containerCursor;
    private final Set<BlockPosition> triedStorage = new LinkedHashSet<>();
    private final List<ContainerTarget> storageRoute = new ArrayList<>();
    private final Set<String> deferredCacheCategories = new LinkedHashSet<>();
    private int markerCursor;
    private UUID markerZoneId;
    private final Set<BlockPosition> readMarkerPositions =
            new LinkedHashSet<>();
    private boolean cacheHasMore;
    private String lastContainerRead = "";

    public TaskRunWorkspace() {
        this(new PlayerInventoryAccount(UUID.randomUUID()));
    }

    public TaskRunWorkspace(PlayerInventoryAccount inventoryAccount) {
        this.inventoryAccount = java.util.Objects.requireNonNull(inventoryAccount);
    }

    public PlayerInventoryAccount inventoryAccount() {
        return inventoryAccount;
    }

    public synchronized void recordCollected(String itemId, int count) {
        inventoryAccount.recordCollected(itemId, count);
    }

    public synchronized Map<String, Integer> collectedItems() {
        return inventoryAccount.itemsForSource("collected");
    }

    public synchronized void captureInitialInventory(
            Map<String, Integer> itemCounts
    ) {
        if (initialInventory == null) {
            initialInventory = new PlayerInventorySnapshot(itemCounts);
            lastInventory = initialInventory;
        }
    }

    public synchronized Optional<PlayerInventorySnapshot> initialInventory() {
        return Optional.ofNullable(initialInventory);
    }

    public synchronized Optional<PlayerInventorySnapshot> lastInventory() {
        return Optional.ofNullable(lastInventory);
    }

    public synchronized void observeInventory(
            Map<String, Integer> itemCounts
    ) {
        lastInventory = new PlayerInventorySnapshot(itemCounts);
    }

    public synchronized void removeCollected(String itemId, int count) {
        inventoryAccount.removeCollected(itemId, count);
    }

    public synchronized void recordWithdrawn(String itemId, int count) {
        inventoryAccount.recordWithdrawn(itemId, count);
    }

    public synchronized void recordManagedAcquired(String itemId, int count) {
        inventoryAccount.recordManagedAcquired(itemId, count);
    }

    public synchronized void consumeManaged(String itemId, int count) {
        inventoryAccount.consumeManaged(itemId, count);
    }

    public synchronized Map<String, Integer> carriedItems() {
        return inventoryAccount.itemsForSource("carried");
    }

    public synchronized Map<String, Integer> managedItems() {
        return inventoryAccount.managedItems();
    }

    public synchronized int managedItemCount() {
        return total(inventoryAccount.managedItems());
    }

    public synchronized boolean hasManagedItems() {
        return !inventoryAccount.managedItems().isEmpty();
    }

    public synchronized boolean hasCarriedItems() {
        return !inventoryAccount.itemsForSource("carried").isEmpty();
    }

    public synchronized Map<String, Integer> itemsForSource(String source) {
        return inventoryAccount.itemsForSource(source);
    }

    public synchronized void consumeDeposit(
            String source,
            Map<String, Integer> requested,
            Map<String, Integer> remaining
    ) {
        inventoryAccount.confirmDeposit(source, requested, remaining);
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
        cacheInventory = Map.of();
        cacheContainers.clear();
        unavailableTargets.clear();
        containerCandidates.clear();
        if (scan != null) {
            containerCandidates.addAll(scan.containers());
        }
        containerCursor = 0;
        triedStorage.clear();
        storageRoute.clear();
        selectedContainer = null;
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
        cacheHasMore = false;
        deferredCacheCategories.clear();
    }

    public synchronized Optional<ContainerScanResult> containerScan() {
        return Optional.ofNullable(containerScan);
    }

    public synchronized Optional<UUID> containerScanRegionId() {
        return Optional.ofNullable(containerScanRegionId);
    }

    public synchronized void recordCacheInventory(
            Map<BlockPosition, Map<String, Integer>> contents) {
        Map<BlockPosition, Map<String, Integer>> copy = new LinkedHashMap<>();
        contents.forEach((position, items) -> copy.put(position, Map.copyOf(items)));
        cacheInventory = Map.copyOf(copy);
    }

    public synchronized boolean hasRecordedClassifiableStock(ContainerTarget target,
            Set<String> categories) {
        Map<String, Integer> items = cacheInventory.get(target.position());
        return items == null || items.keySet().stream()
                .anyMatch(key -> ItemIdentity.matches(key, categories));
    }

    public synchronized void setCategoryMatch(
            List<ContainerTarget> targets,
            Map<ContainerTarget, Set<String>> accepted
    ) {
        targetContainers.clear();
        targetContainers.addAll(targets);
        acceptedItemsByContainer.clear();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Map.Entry<ContainerTarget, Set<String>> entry : accepted.entrySet()) {
            acceptedItemsByContainer.put(
                    entry.getKey(),
                    Set.copyOf(entry.getValue())
            );
            itemIds.addAll(entry.getValue());
        }
        classifiedItemIds = Set.copyOf(itemIds);
        unavailableTargets.clear();
        triedStorage.clear();
        storageRoute.clear();
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
    }

    public synchronized void setResolvedCategories(
            List<ContainerTarget> targets,
            Map<ContainerTarget, Set<String>> accepted,
            List<ContainerTarget> cacheTargets
    ) {
        targetContainers.clear();
        targetContainers.addAll(targets);
        containerCandidates.clear();
        containerCandidates.addAll(cacheTargets);
        cacheContainers.clear();
        cacheContainers.addAll(cacheTargets);
        acceptedItemsByContainer.clear();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Map.Entry<ContainerTarget, Set<String>> entry : accepted.entrySet()) {
            acceptedItemsByContainer.put(
                    entry.getKey(),
                    Set.copyOf(entry.getValue())
            );
            itemIds.addAll(entry.getValue());
        }
        classifiedItemIds = Set.copyOf(itemIds);
        unavailableTargets.clear();
        containerCursor = 0;
        triedStorage.clear();
        storageRoute.clear();
        selectedContainer = null;
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
        cacheHasMore = false;
        deferredCacheCategories.clear();
    }

    public synchronized Map<ContainerTarget, Set<String>> acceptedItemsByContainer() {
        Map<ContainerTarget, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<ContainerTarget, Set<String>> entry
                : acceptedItemsByContainer.entrySet()) {
            result.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    public synchronized List<ContainerTarget> cacheContainers() {
        return List.copyOf(cacheContainers);
    }

    public synchronized boolean hasContainers() {
        return containerCursor < containerCandidates.size();
    }

    public synchronized Optional<ContainerTarget> selectNextContainer() {
        if (!hasContainers()) {
            selectedContainer = null;
            storageUsesCacheFallback = false;
            return Optional.empty();
        }
        selectedContainer = containerCandidates.get(containerCursor++);
        deferredCacheCategories.clear();
        triedStorage.clear();
        storageRoute.clear();
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
        cacheHasMore = true;
        return Optional.of(selectedContainer);
    }

    public synchronized Optional<ContainerTarget> selectedContainer() {
        return Optional.ofNullable(selectedContainer);
    }

    public synchronized Set<String> selectedContainerItemIds() {
        if (selectedContainer == null) {
            return Set.of();
        }
        return withdrawItemIdsFor(selectedContainer);
    }

    public synchronized Set<String> withdrawItemIdsFor(
            ContainerTarget target
    ) {
        for (Map.Entry<ContainerTarget, Set<String>> entry
                : acceptedItemsByContainer.entrySet()) {
            if (entry.getKey().position().equals(target.position())) {
                return Set.copyOf(entry.getValue());
            }
        }
        for (ContainerTarget candidate : containerCandidates) {
            if (candidate.position().equals(target.position())) {
                return classifiedItemIds;
            }
        }
        return Set.of();
    }

    public synchronized Set<String> remainingCacheCategories(ContainerTarget target) {
        Set<String> categories = new LinkedHashSet<>(withdrawItemIdsFor(target));
        if (selectedContainer != null && selectedContainer.position().equals(target.position())) {
            categories.removeIf(key ->
                    deferredCacheCategories.contains(ItemIdentity.category(key)));
        }
        return Set.copyOf(categories);
    }

    public synchronized void deferReturnedCacheItems(Map<String, Integer> requested,
            Map<String, Integer> remaining) {
        requested.forEach((key, count) -> {
            if (count > remaining.getOrDefault(key, 0)) {
                deferredCacheCategories.add(ItemIdentity.category(key));
            }
        });
    }

    public synchronized void setCacheHasMore(boolean value) {
        cacheHasMore = value;
    }

    public synchronized boolean cacheHasMore() {
        return cacheHasMore;
    }

    public synchronized Optional<ContainerTarget> selectStorageTarget() {
        return selectStorageTarget("carried");
    }

    public synchronized Optional<ContainerTarget> selectStorageTarget(
            String itemSource
    ) {
        return selectStorageTarget(itemSource, false);
    }

    public synchronized Optional<ContainerTarget> selectStorageTarget(
            String itemSource,
            boolean allowCacheFallback
    ) {
        return selectStorageTarget(itemSource, allowCacheFallback, (first, second) -> 0);
    }

    public synchronized Optional<ContainerTarget> selectStorageTarget(String itemSource,
            boolean allowCacheFallback, Comparator<ContainerTarget> order) {
        Map<String, Integer> items = itemsForSource(itemSource);
        if (items.isEmpty()) {
            selectedStorageTarget = null;
            storageUsesCacheFallback = false;
            return Optional.empty();
        }
        List<ContainerTarget> candidates = targetContainers.stream()
                .filter(candidate -> !triedStorage.contains(candidate.position()))
                .filter(candidate -> !isUnavailable(candidate))
                .filter(candidate -> selectedContainer == null
                        || !selectedContainer.position().equals(candidate.position()))
                .filter(candidate -> items.keySet().stream().anyMatch(itemId ->
                        dev.stowling.automation.ItemIdentity.matches(itemId,
                                acceptedItemsByContainer.getOrDefault(candidate, Set.of()))))
                .toList();
        Optional<ContainerTarget> next;
        if ("carried".equals(itemSource) && selectedContainer != null
                && !cacheContainers.isEmpty()) {
            storageRoute.removeIf(target -> !candidates.contains(target));
            if (storageRoute.isEmpty()) {
                BlockPosition start = selectedStorageTarget == null
                        ? selectedContainer.position() : selectedStorageTarget.position();
                storageRoute.addAll(ClassificationRoutePlanner.plan(start,
                        selectedContainer.position(), candidates, acceptedItemsByContainer,
                        items.keySet(), order));
            }
            next = storageRoute.isEmpty() ? Optional.empty()
                    : Optional.of(storageRoute.removeFirst());
        } else {
            next = candidates.stream().min(order);
        }
        if (next.isPresent()) {
            selectedStorageTarget = next.orElseThrow();
            triedStorage.add(selectedStorageTarget.position());
            storageUsesCacheFallback = false;
            return next;
        }
        if (allowCacheFallback
                && selectedContainer != null
                && !isUnavailable(selectedContainer)
                && cacheContainers.stream().anyMatch(target ->
                        target.position().equals(selectedContainer.position())
                )) {
            selectedStorageTarget = selectedContainer;
            storageUsesCacheFallback = true;
            return Optional.of(selectedContainer);
        }
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
        return Optional.empty();
    }

    public synchronized Optional<ContainerTarget> selectedStorageTarget() {
        return Optional.ofNullable(selectedStorageTarget);
    }

    public synchronized boolean selectedStorageUsesCacheFallback() {
        return storageUsesCacheFallback;
    }

    public synchronized boolean isClassificationStorage(ContainerTarget target) {
        return !storageUsesCacheFallback && target.equals(selectedStorageTarget)
                && selectedContainer != null && !cacheContainers.isEmpty();
    }

    public synchronized void resetStorageSelection() {
        triedStorage.clear();
        storageRoute.clear();
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
    }

    public synchronized void markTargetUnavailable(ContainerTarget target) {
        if (target != null) {
            unavailableTargets.add(target);
        }
    }

    public synchronized boolean isRejectedStorage(ContainerTarget target) {
        return target.equals(selectedStorageTarget) && isUnavailable(target);
    }

    private boolean isUnavailable(ContainerTarget target) {
        return unavailableTargets.stream().anyMatch(unavailable ->
                unavailable.position().equals(target.position())
        );
    }

    public synchronized void setMarkerPlan(
            UUID zoneId,
            MarkerContainerScanAction.Plan plan
    ) {
        markerZoneId = zoneId;
        markerTargets.clear();
        markerTargets.addAll(plan.targets());
        markerCache = copyMarkerCache(plan.cache());
        markerCursor = 0;
        selectedMarkerContainer = null;
        readMarkerPositions.clear();
    }

    public synchronized Optional<UUID> markerZoneId() {
        return Optional.ofNullable(markerZoneId);
    }

    public synchronized int markerTargetCount() {
        return markerTargets.size();
    }

    public synchronized int markerReadCount() {
        return readMarkerPositions.size();
    }

    public synchronized boolean allMarkerContainersRead() {
        for (MarkerContainerScanAction.Target target : markerTargets) {
            if (!readMarkerPositions.contains(target.target().position())) {
                return false;
            }
        }
        return !markerTargets.isEmpty();
    }

    public synchronized boolean hasMarkerContainers() {
        return markerCursor < markerTargets.size();
    }

    public synchronized Optional<MarkerContainerScanAction.Target> selectMarker() {
        if (!hasMarkerContainers()) {
            selectedMarkerContainer = null;
            return Optional.empty();
        }
        MarkerContainerScanAction.Target target = markerTargets.get(markerCursor++);
        selectedMarkerContainer = target.target();
        return Optional.of(target);
    }

    public synchronized Optional<ContainerTarget> selectedMarkerContainer() {
        return Optional.ofNullable(selectedMarkerContainer);
    }

    public synchronized void recordContainerRead(
            ContainerTarget target,
            Set<String> itemIds
    ) {
        MarkerContainerScanAction.Target marker = markerTargets.stream()
                .filter(candidate -> candidate.target().equals(target))
                .findFirst()
                .orElse(null);
        if (marker != null) {
            for (UUID bindingId : marker.bindingIds()) {
                markerCache.computeIfAbsent(
                        bindingId,
                        ignored -> new LinkedHashMap<>()
                ).put(target.position(), Set.copyOf(itemIds));
            }
            readMarkerPositions.add(target.position());
        }
        lastContainerRead = String.join(",", itemIds);
    }

    public synchronized String lastContainerRead() {
        return lastContainerRead;
    }

    public synchronized void saveMarkerCache() {
        markerCache = copyMarkerCache(markerCache);
    }

    public synchronized Map<UUID, Map<BlockPosition, Set<String>>> markerCache() {
        return copyMarkerCache(markerCache);
    }

    public synchronized void recordCacheUpdate(UUID regionId, int itemCount) {
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

    public synchronized Map<String, Integer> copyLedger(String source) {
        return itemsForSource(source);
    }

    private static int total(Map<String, Integer> ledger) {
        int result = 0;
        for (int count : ledger.values()) {
            result = Math.addExact(result, count);
        }
        return result;
    }

    private static Map<UUID, Map<BlockPosition, Set<String>>> copyMarkerCache(
            Map<UUID, Map<BlockPosition, Set<String>>> source
    ) {
        Map<UUID, Map<BlockPosition, Set<String>>> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<BlockPosition, Set<String>>> entry
                : source.entrySet()) {
            result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return result;
    }
}



