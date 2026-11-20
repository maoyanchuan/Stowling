package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ClassificationRoutePlanner;
import dev.stowling.automation.ClassificationLayoutEntry;
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
    private dev.stowling.v2.runtime.TaskDiagnosticLog diagnostics;

    public synchronized void setDiagnostics(dev.stowling.v2.runtime.TaskDiagnosticLog value) {
        diagnostics = value;
    }

    public synchronized dev.stowling.v2.runtime.TaskDiagnosticLog diagnostics() {
        return diagnostics;
    }
    private final PlayerInventoryAccount inventoryAccount;
    private dev.stowling.v2.model.TaskCleanupPolicy cleanupPolicy =
            dev.stowling.v2.model.TaskCleanupPolicy.DEFAULT;
    private final TaskProgress progress = new TaskProgress();
    private final Map<UUID, Integer> cacheUpdates = new LinkedHashMap<>();
    private final Map<ContainerTarget, Set<String>> acceptedItemsByContainer =
            new LinkedHashMap<>();
    private final List<ContainerTarget> targetContainers = new ArrayList<>();
    private List<ClassificationLayoutEntry> classificationLayout = List.of();
    private boolean unstackableClassification;
    private final List<ContainerTarget> containerCandidates = new ArrayList<>();
    private final List<ContainerTarget> cacheContainers = new ArrayList<>();
    private Set<BlockPosition> partialBoxStoragePositions = Set.of();
    private final Set<ContainerTarget> unavailableTargets =
            new LinkedHashSet<>();
    private String storageFailureReason = "";
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

    public synchronized void configureCleanup(dev.stowling.v2.model.TaskCleanupPolicy policy) {
        cleanupPolicy = policy;
    }

    public synchronized dev.stowling.v2.model.TaskCleanupPolicy cleanupPolicy() {
        return cleanupPolicy;
    }

    public TaskProgress progress() {
        return progress;
    }

    public synchronized void invalidateCacheInventory() {
        cacheInventory = Map.of();
        deferredCacheCategories.clear();
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
        classificationLayout = List.of();
        unstackableClassification = false;
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

    public synchronized void refreshContainerScan(UUID regionId, ContainerScanResult scan) {
        Map<BlockPosition, Map<String, Integer>> known = new LinkedHashMap<>();
        if (regionId.equals(containerScanRegionId) && scan.succeeded() && containerScan != null
                && containerScan.succeeded()) {
            for (ContainerTarget target : scan.containers()) {
                if (containerScan.containers().contains(target)
                        && cacheInventory.containsKey(target.position())) {
                    known.put(target.position(), cacheInventory.get(target.position()));
                }
            }
        }
        setContainerScan(regionId, scan);
        recordCacheInventory(known);
    }

    public synchronized Optional<Map<String, Integer>> cacheContents(ContainerTarget target) {
        return Optional.ofNullable(cacheInventory.get(target.position()));
    }

    public synchronized void recordCacheTransfer(ContainerTarget target, Map<String, Integer> moved,
            boolean withdraw) {
        Map<String, Integer> previous = cacheInventory.get(target.position());
        if (previous == null) return;
        if (withdraw && moved.entrySet().stream().anyMatch(entry ->
                entry.getValue() > previous.getOrDefault(entry.getKey(), 0))) {
            Map<BlockPosition, Map<String, Integer>> remaining = new LinkedHashMap<>(cacheInventory);
            remaining.remove(target.position());
            cacheInventory = Map.copyOf(remaining);
            return;
        }
        Map<String, Integer> items = new LinkedHashMap<>(previous);
        for (var entry : moved.entrySet()) {
            int count = items.getOrDefault(entry.getKey(), 0)
                    + (withdraw ? -entry.getValue() : entry.getValue());
            if (count > 0) items.put(entry.getKey(), count);
            else items.remove(entry.getKey());
        }
        recordCacheContents(target, items);
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
                .anyMatch(key -> (unstackableClassification
                            ? ItemIdentity.isBox(key) && !ItemIdentity.isEmptyBox(key)
                            : ItemIdentity.isMixedBox(key))
                        || ItemIdentity.matches(key, categories));
    }

    public synchronized boolean hasMixedCacheStock(ContainerTarget target) {
        return cacheInventory.getOrDefault(target.position(), Map.of()).keySet().stream()
                .anyMatch(ItemIdentity::isMixedBox);
    }

    public synchronized void recordCacheContents(ContainerTarget target,
            Map<String, Integer> items) {
        Map<BlockPosition, Map<String, Integer>> copy = new LinkedHashMap<>(cacheInventory);
        copy.put(target.position(), Map.copyOf(items));
        cacheInventory = Map.copyOf(copy);
    }

    public synchronized void setCategoryMatch(
            List<ContainerTarget> targets,
            Map<ContainerTarget, Set<String>> accepted
    ) {
        classificationLayout = List.of();
        unstackableClassification = false;
        partialBoxStoragePositions = Set.of();
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
        setResolvedCategories(targets, accepted, cacheTargets, List.of());
    }

    public synchronized void setResolvedCategories(
            List<ContainerTarget> targets,
            Map<ContainerTarget, Set<String>> accepted,
            List<ContainerTarget> cacheTargets,
            List<ClassificationLayoutEntry> layout
    ) {
        setResolvedCategories(targets, accepted, cacheTargets, layout, Set.of());
    }

    public synchronized void setResolvedCategories(
            List<ContainerTarget> targets,
            Map<ContainerTarget, Set<String>> accepted,
            List<ContainerTarget> cacheTargets,
            List<ClassificationLayoutEntry> layout,
            Set<BlockPosition> partialBoxStoragePositions
    ) {
        classificationLayout = List.copyOf(layout);
        unstackableClassification = false;
        this.partialBoxStoragePositions = Set.copyOf(partialBoxStoragePositions);
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

    public synchronized void setUnstackableClassification(List<ContainerTarget> targets,
            Map<ContainerTarget, Set<String>> accepted, List<ContainerTarget> cacheTargets) {
        setResolvedCategories(targets, accepted, cacheTargets);
        unstackableClassification = true;
    }

    public synchronized boolean unstackableClassification() {
        return unstackableClassification;
    }

    public synchronized List<String> classificationItemOrder(Set<String> items) {
        Set<String> result = new LinkedHashSet<>();
        for (ClassificationLayoutEntry entry : classificationLayout) {
            boolean available = entry.containers().stream().anyMatch(target ->
                    !isUnavailable(target) && (selectedContainer == null
                            || !target.position().equals(selectedContainer.position())));
            if (!available) continue;
            items.stream().filter(key -> ItemIdentity.matches(key, Set.of(entry.itemId())))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    public synchronized Map<ContainerTarget, Set<String>> acceptedItemsByContainer() {
        Map<ContainerTarget, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<ContainerTarget, Set<String>> entry
                : acceptedItemsByContainer.entrySet()) {
            result.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    public synchronized List<ContainerTarget> classificationTargets(String itemId) {
        Set<ContainerTarget> ordered = new LinkedHashSet<>();
        for (ClassificationLayoutEntry entry : classificationLayout) {
            if (ItemIdentity.matches(itemId, Set.of(entry.itemId()))) {
                ordered.addAll(entry.containers());
            }
        }
        ordered.addAll(targetContainers);
        return ordered.stream().filter(target -> !isUnavailable(target))
                .filter(target -> ItemIdentity.matches(itemId,
                        acceptedItemsByContainer.getOrDefault(target, Set.of())))
                .filter(target -> selectedContainer == null
                        || target.entrances().stream()
                                .noneMatch(selectedContainer.entrances()::contains)).toList();
    }

    public synchronized List<ContainerTarget> cacheContainers() {
        return List.copyOf(cacheContainers);
    }

    public synchronized Set<BlockPosition> partialBoxStoragePositions() {
        return partialBoxStoragePositions;
    }

    public synchronized boolean hasContainers() {
        while (!cacheContainers.isEmpty() && containerCursor < containerCandidates.size()
                && !hasRecordedClassifiableStock(containerCandidates.get(containerCursor),
                        classifiedItemIds)) {
            containerCursor++;
        }
        return containerCursor < containerCandidates.size();
    }

    public synchronized Optional<ContainerTarget> selectNextContainer() {
        if (!hasContainers()) {
            selectedContainer = null;
            storageUsesCacheFallback = false;
            return Optional.empty();
        }
        selectedContainer = containerCandidates.get(containerCursor++);
        storageFailureReason = "";
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
                    deferredCacheCategories.contains(unstackableClassification
                            ? key : ItemIdentity.category(key)));
        }
        return Set.copyOf(categories);
    }

    public synchronized Set<String> mixedBoxWithdrawalItems(ContainerTarget cache,
            Set<String> contents) {
        Set<String> categories = remainingCacheCategories(cache);
        Set<String> selected = new LinkedHashSet<>();
        for (String key : contents) {
            if (!ItemIdentity.matches(key, categories)) {
                continue;
            }
            boolean available = targetContainers.stream()
                    .filter(target -> !target.position().equals(cache.position()))
                    .filter(target -> !isUnavailable(target))
                    .anyMatch(target -> ItemIdentity.matches(key,
                            acceptedItemsByContainer.getOrDefault(target, Set.of())));
            if (available) {
                selected.add(key);
            }
        }
        return Set.copyOf(selected);
    }

    public synchronized void deferReturnedCacheItems(Map<String, Integer> requested,
            Map<String, Integer> remaining) {
        requested.forEach((key, count) -> {
            if (count > remaining.getOrDefault(key, 0)) {
                deferredCacheCategories.add(unstackableClassification
                        ? ItemIdentity.exact(key) : ItemIdentity.category(key));
            }
        });
    }

    public synchronized void setCacheHasMore(boolean value) {
        cacheHasMore = value;
    }

    public synchronized boolean cacheHasMore() {
        return cacheHasMore;
    }

    public synchronized void deferCacheCategory(String category) {
        deferredCacheCategories.add(category);
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
        if (!classificationLayout.isEmpty()) {
            next = ClassificationRoutePlanner.planLayout(classificationLayout, candidates,
                    acceptedItemsByContainer, items.keySet(), order).stream().findFirst();
        } else if ("carried".equals(itemSource) && selectedContainer != null
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
        storageFailureReason = "";
        triedStorage.clear();
        storageRoute.clear();
        selectedStorageTarget = null;
        storageUsesCacheFallback = false;
    }

    public synchronized void markTargetUnavailable(ContainerTarget target) {
        markTargetUnavailable(target, "目标容器已满或不可用");
    }

    public synchronized void markTargetUnavailable(ContainerTarget target, String reason) {
        if (target != null) {
            unavailableTargets.add(target);
            storageFailureReason = target.position() + "：" + reason;
        }
    }

    public synchronized String storageFailureReason() {
        return storageFailureReason;
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
                ).put(target.position(), java.util.Collections.unmodifiableSet(
                        new LinkedHashSet<>(itemIds)));
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



