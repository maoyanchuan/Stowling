package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates a recoverable multi-region pickup workflow.
 *
 * <p>Only confirmed high-level progress is persisted. An in-flight container
 * click is discarded during recovery and rebuilt from the authoritative screen.
 */
public final class PickupAutomationEngine {
    private final PickupAutomationPlan plan;
    private final WorldObserver worldObserver;
    private final PlayerInventoryAdapter inventoryAdapter;
    private final NavigationPort navigation;
    private final ContainerInteraction containers;
    private final PackingPort packing;
    private final AutomationClock clock;
    private final AutomationCheckpointStore checkpoints;
    private final DeliveryPlanner deliveryPlanner = new DeliveryPlanner();
    private final StorageRouteMatcher routeMatcher = new StorageRouteMatcher();
    private final List<PickupRegionBinding> regionOrder;
    private final List<ContainerRef> workflowCaches;

    private final Set<String> observedInputs = new HashSet<>();
    private final Set<String> failedCacheIds = new HashSet<>();
    private final Map<String, Instant> regionIdleSince = new LinkedHashMap<>();
    // A region round is bounded to the entity IDs visible at first collection.
    // Later drops remain for a later round and cannot extend this collection.
    private final Map<String, Set<String>> regionTargetKeys = new HashMap<>();
    private final Map<String, Instant> collectionDeadlines = new HashMap<>();
    private Map<String, Integer> inventoryBaseline = Map.of();
    private Optional<Instant> workflowIdleSince = Optional.empty();
    private List<DeliveryIntent> deliveries = List.of();
    private int deliveryIndex;
    private int currentRegionIndex;
    private int selectedCacheIndex;
    private int classificationCacheIndex;
    private int classificationFailures;
    private long round = 1;
    private boolean classificationAfterRound;
    private ContainerFlow containerFlow;
    private PickupStage stage = PickupStage.NAVIGATE_PICKUP;
    private PickupStage resumeStage = PickupStage.NAVIGATE_PICKUP;
    private Optional<AutomationPauseReason> pauseReason = Optional.empty();
    private Optional<BlockPosition> navigationTarget = Optional.empty();
    private NavigationTargetType navigationTargetType = NavigationTargetType.STANDARD;
    private Optional<Instant> navigationStartedAt = Optional.empty();
    private Optional<Instant> containerObservationStartedAt = Optional.empty();
    private boolean started;
    private boolean stopRequested;
    private StopOutcome stopOutcome = StopOutcome.CLEAN;
    private boolean navigationStopActive;
    private StopOutcome navigationStopOutcome = StopOutcome.CLEAN;
    private boolean containerStopActive;
    private StopOutcome containerStopOutcome = StopOutcome.CLEAN;
    private ContainerAttempt stoppingContainerAttempt;
    private boolean packingStopActive;
    private StopOutcome packingStopOutcome = StopOutcome.CLEAN;
    private PackingFlow packingFlow;
    private boolean containerStopUnconfirmed;

    public PickupAutomationEngine(
            PickupAutomationPlan plan,
            WorldObserver worldObserver,
            PlayerInventoryAdapter inventoryAdapter,
            NavigationPort navigation,
            ContainerInteraction containers,
            PackingPort packing,
            AutomationClock clock,
            AutomationCheckpointStore checkpoints
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.worldObserver = Objects.requireNonNull(worldObserver, "worldObserver");
        this.inventoryAdapter = Objects.requireNonNull(inventoryAdapter, "inventoryAdapter");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.containers = Objects.requireNonNull(containers, "containers");
        this.packing = Objects.requireNonNull(packing, "packing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        regionOrder = plan.orderedRegions();
        workflowCaches = plan.caches();
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Optional<AutomationCheckpoint> stored = checkpoints.load(plan.taskId());
        if (stored.isPresent()) {
            restore(stored.orElseThrow());
            reconcileAfterRecovery();
        } else {
            try {
                inventoryBaseline = initialInventoryBaseline(inventoryAdapter.observe());
            } catch (StorageRouteMatchException exception) {
                pause("ROUTE_CONFLICT", "multiple storage routes claim an item");
                return;
            }
        }
        saveCheckpoint();
    }

    private Map<String, Integer> initialInventoryBaseline(
            PlayerInventorySnapshot inventory
    ) {
        Map<String, Integer> baseline = new HashMap<>();
        for (Map.Entry<String, Integer> entry : inventory.itemCounts().entrySet()) {
            Optional<StorageRoute> route = routeMatcher.match(
                    entry.getKey(),
                    worldObserver.tagsFor(entry.getKey()),
                    plan.storageRoutes()
            );
            if (route.isEmpty()) {
                baseline.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(baseline);
    }

    public synchronized AutomationSnapshot tick() {
        start();
        if (stopRequested || isTerminalOrPaused()) {
            return snapshot();
        }
        if (containerStopActive) {
            pollContainerStop();
            saveCheckpoint();
            return snapshot();
        }
        if (!checkPlayerState()) {
            return snapshot();
        }
        switch (stage) {
            case NAVIGATE_PICKUP -> tickNavigatePickup();
            case COLLECT -> tickCollect();
            case DEPOSIT_BUFFER -> tickDepositBuffer();
            case WAIT_FOR_IDLE -> tickWaitForIdle();
            case CLASSIFY_BUFFER -> tickClassifyBuffer();
            case DELIVER_LOOSE -> tickDeliverLoose();
            case PACK -> tickPack();
            case COMPLETE, PAUSED, FAILED -> throw new IllegalStateException(
                    "terminal stage reached dispatch"
            );
            default -> throw new IllegalStateException("unsupported stage: " + stage);
        }
        saveCheckpoint();
        return snapshot();
    }

    public synchronized void resume() {
        if (stage != PickupStage.PAUSED) {
            return;
        }
        stage = resumeStage;
        pauseReason = Optional.empty();
        failedCacheIds.clear();
        classificationFailures = 0;
        reconcileAfterRecovery();
        saveCheckpoint();
    }

    public synchronized void pauseForControl() {
        pause("CONTROL_PAUSED", "automation paused by the control surface");
    }

    public synchronized void cancel() {
        requestStop();
    }

    public synchronized void requestStop() {
        if (containerStopUnconfirmed) {
            stopRequested = true;
            stopOutcome = StopOutcome.UNSAFE;
            finishStop();
            return;
        }
        if ((stage == PickupStage.COMPLETE || stage == PickupStage.FAILED)
                && !navigationStopActive
                && !containerStopActive
                && !packingStopActive) {
            stopOutcome = StopOutcome.CLEAN;
            return;
        }
        if (stopRequested) {
            return;
        }
        stopRequested = true;
        stopOutcome = StopOutcome.PENDING;
        requestNavigationStop();
        requestContainerStop();
        requestPackingStop();
        pollNavigationStop();
        pollContainerStop();
        pollPackingStop();
        updateStopOutcome();
    }

    public synchronized StopOutcome pollStop() {
        if (!stopRequested) {
            return StopOutcome.CLEAN;
        }
        if (stopOutcome != StopOutcome.PENDING) {
            return stopOutcome;
        }
        pollNavigationStop();
        pollContainerStop();
        pollPackingStop();
        updateStopOutcome();
        return stopOutcome;
    }

    public synchronized AutomationSnapshot snapshot() {
        return new AutomationSnapshot(
                plan.taskId(),
                stage,
                pauseReason,
                stage == PickupStage.PAUSED ? Optional.of(resumeStage) : Optional.empty(),
                activeDelivery(),
                observedInputs.size(),
                Math.max(0, deliveries.size() - deliveryIndex),
                plan.workflow().id(),
                Optional.of(currentRegion().id()),
                round
        );
    }

    private boolean isTerminalOrPaused() {
        return stage == PickupStage.COMPLETE
                || stage == PickupStage.FAILED
                || stage == PickupStage.PAUSED;
    }

    private void tickNavigatePickup() {
        PickupRegion region = currentRegion();
        NavigationStatus status = moveTo(region.standbyPosition());
        if (status == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to pickup region " + region.id() + " failed");
            return;
        }
        if (status == NavigationStatus.ARRIVED) {
            clearNavigation();
            enter(PickupStage.COLLECT);
        }
    }

    private void tickCollect() {
        List<GroundItem> items = observeInputs();
        if (!items.isEmpty() && collectionTimedOut()) {
            fail(
                    "COLLECTION_TIMEOUT",
                    "region collection exceeded its bounded round deadline"
            );
            return;
        }
        PlayerInventorySnapshot inventory = inventoryAdapter.observe();
        if (inventory.freeSlots() <= plan.inventoryReserveSlots()) {
            clearNavigation();
            beginDeposit();
            return;
        }
        if (items.isEmpty()) {
            clearNavigation();
            if (!inventoryDelta(inventory).isEmpty()) {
                beginDeposit();
            } else {
                startRegionIdleTimer();
                enter(PickupStage.WAIT_FOR_IDLE);
            }
            return;
        }
        GroundItem nearest = nearestItem(items, currentPosition());
        NavigationStatus status = moveTo(
                nearest.position(),
                NavigationTargetType.GROUND_ITEM
        );
        if (status == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to a ground item failed");
            return;
        }
        if (status == NavigationStatus.ARRIVED) {
            clearNavigation();
        }
    }

    private GroundItem nearestItem(List<GroundItem> items, BlockPosition origin) {
        GroundItem nearest = items.getFirst();
        long nearestDistance = distanceSquared(origin, nearest.position());
        for (GroundItem item : items) {
            long distance = distanceSquared(origin, item.position());
            if (distance < nearestDistance) {
                nearest = item;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void tickDepositBuffer() {
        ContainerRef buffer = selectedCache();
        NavigationStatus travel = moveTo(buffer.position());
        if (travel == NavigationStatus.FAILED) {
            depositCacheFailed(
                    "PATH_FAILED",
                    "navigation to pickup cache " + buffer.id() + " failed"
            );
            return;
        }
        if (travel != NavigationStatus.ARRIVED) {
            return;
        }
        clearNavigation();
        PlayerInventorySnapshot inventory = inventoryAdapter.observe();
        List<ItemAmount> collected = inventoryDelta(inventory);
        if (collected.isEmpty()) {
            inventoryBaseline = inventory.itemCounts();
            startRegionIdleTimer();
            enter(PickupStage.WAIT_FOR_IDLE);
            return;
        }
        ContainerTransfer transfer = new ContainerTransfer(
                plan.taskId() + ":deposit-cache:" + buffer.id(),
                Optional.empty(),
                buffer,
                collected
        );
        TransferResult result = transfer(
                transfer,
                "BUFFER_UNAVAILABLE",
                TransferMode.DEPOSIT_CACHE
        );
        switch (result.status()) {
            case ACKNOWLEDGED -> {
                inventoryBaseline = inventoryAdapter.observe().itemCounts();
                startRegionIdleTimer();
                enter(PickupStage.WAIT_FOR_IDLE);
            }
            case PENDING -> {
                // Wait for the authoritative destination revision.
            }
            case FAILED -> depositCacheFailed(
                    result.failureCode(),
                    "cache " + buffer.id() + " rejected the deposit"
            );
        }
    }

    private void tickWaitForIdle() {
        List<GroundItem> items = observeInputs();
        if (!items.isEmpty()) {
            if (collectionTimedOut()) {
                fail(
                        "COLLECTION_TIMEOUT",
                        "region collection exceeded its bounded round deadline"
                );
                return;
            }
            enter(PickupStage.COLLECT);
            return;
        }
        PlayerInventorySnapshot inventory = inventoryAdapter.observe();
        if (!inventoryDelta(inventory).isEmpty()) {
            beginDeposit();
            return;
        }
        startRegionIdleTimer();
        Instant idleStart = regionIdleSince.get(currentRegion().id());
        Duration idle = Duration.between(idleStart, clock.now());
        if (idle.compareTo(currentBinding().idleDelay()) >= 0) {
            finishRegion();
        }
    }

    private void finishRegion() {
        if (plan.workflow().classificationTiming() == ClassificationTiming.AFTER_REGION) {
            beginClassification(false);
            return;
        }
        advanceRegion();
    }

    private void advanceRegion() {
        currentRegionIndex++;
        if (currentRegionIndex < regionOrder.size()) {
            enter(PickupStage.NAVIGATE_PICKUP);
            return;
        }
        if (plan.workflow().classificationTiming() == ClassificationTiming.AFTER_ROUND) {
            currentRegionIndex = regionOrder.size() - 1;
            beginClassification(true);
            return;
        }
        finishRound();
    }

    private void beginClassification(boolean afterRound) {
        classificationAfterRound = afterRound;
        classificationCacheIndex = 0;
        classificationFailures = 0;
        deliveries = List.of();
        deliveryIndex = 0;
        enter(PickupStage.CLASSIFY_BUFFER);
    }

    private void tickClassifyBuffer() {
        if (classificationCacheIndex >= workflowCaches.size()) {
            finishClassification();
            return;
        }
        ContainerRef buffer = workflowCaches.get(classificationCacheIndex);
        NavigationStatus travel = moveTo(buffer.position());
        if (travel == NavigationStatus.FAILED) {
            retryClassificationOrPause(
                    "PATH_FAILED",
                    "navigation to cache " + buffer.id() + " failed"
            );
            return;
        }
        if (travel != NavigationStatus.ARRIVED) {
            return;
        }
        clearNavigation();
        containers.prepare(buffer);
        Optional<ContainerView> view = containers.observe(buffer);
        if (view.isEmpty()) {
            if (containerObservationTimedOut()) {
                retryClassificationOrPause(
                        "BUFFER_UNAVAILABLE",
                        "cache " + buffer.id() + " could not be observed"
                );
            }
            return;
        }
        classificationFailures = 0;
        containerObservationStartedAt = Optional.empty();
        if (view.orElseThrow().isEmpty()) {
            classificationCacheIndex++;
            deliveries = List.of();
            deliveryIndex = 0;
            if (classificationCacheIndex >= workflowCaches.size()) {
                finishClassification();
            }
            return;
        }
        try {
            deliveries = deliveryPlanner.plan(
                    plan.taskId(),
                    buffer,
                    view.orElseThrow(),
                    plan.storageRoutes(),
                    plan.overflowContainer(),
                    worldObserver::tagsFor
            );
        } catch (StorageRouteMatchException exception) {
            pause("ROUTE_CONFLICT", "multiple storage routes claim an item");
            return;
        }
        deliveryIndex = 0;
        enterCurrentDeliveryStage();
    }

    private void finishClassification() {
        if (classificationAfterRound) {
            finishRound();
        } else {
            advanceRegion();
        }
    }

    private void finishRound() {
        currentRegionIndex = 0;
        if (plan.workflow().completionMode() == CompletionMode.ALL_IDLE) {
            if (workflowIdleSince.isEmpty()) {
                workflowIdleSince = Optional.of(clock.now());
            }
            Duration idle = Duration.between(workflowIdleSince.orElseThrow(), clock.now());
            if (idle.compareTo(plan.workflow().completionIdleDelay()) >= 0) {
                enter(PickupStage.COMPLETE);
                return;
            }
        }
        regionTargetKeys.clear();
        collectionDeadlines.clear();
        round++;
        failedCacheIds.clear();
        enter(PickupStage.NAVIGATE_PICKUP);
    }

    private void tickDeliverLoose() {
        DeliveryIntent intent = activeDelivery().orElseThrow();
        NavigationStatus travel = moveTo(intent.buffer().position());
        if (travel == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to buffer delivery failed");
            return;
        }
        if (travel != NavigationStatus.ARRIVED) {
            return;
        }
        clearNavigation();
        String failureCode = isOverflow(intent)
                ? "OVERFLOW_UNAVAILABLE"
                : "DELIVERY_CONTAINER_UNAVAILABLE";
        TransferResult result = transfer(
                intent.asTransfer(),
                failureCode,
                TransferMode.DELIVERY
        );
        switch (result.status()) {
            case ACKNOWLEDGED -> {
                deliveryIndex++;
                enterCurrentDeliveryStage();
            }
            case PENDING -> {
                // Keep one in-flight click until its server acknowledgement arrives.
            }
            case FAILED -> pause(
                    result.failureCode(),
                    "storage destination exhausted its retry budget"
            );
        }
    }

    private void tickPack() {
        DeliveryIntent intent = activeDelivery().orElseThrow();
        NavigationStatus travel = moveTo(intent.buffer().position());
        if (travel == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to packing buffer failed");
            return;
        }
        if (travel != NavigationStatus.ARRIVED) {
            return;
        }
        clearNavigation();
        PackingRequest request = new PackingRequest(
                plan.taskId(),
                intent.buffer(),
                intent.routeId().orElseThrow(),
                intent.packingStationIds(),
                intent.items()
        );
        PackingDisposition disposition;
        if (packingFlow == null || !packingFlow.request().equals(request)) {
            packingFlow = new PackingFlow(request);
            disposition = packing.submit(request);
        } else {
            disposition = packing.poll(request);
        }
        switch (disposition) {
            case ACCEPTED -> {
                packingFlow = null;
                deliveryIndex++;
                enterCurrentDeliveryStage();
            }
            case PENDING -> {
                // Packing advances only after confirmed server-side changes.
            }
            case UNAVAILABLE -> {
                packingFlow = null;
                pause(
                        "PACKING_UNAVAILABLE",
                        "packing station rejected the delivery"
                );
            }
        }
    }

    private TransferResult transfer(
            ContainerTransfer transfer,
            String unavailableCode,
            TransferMode mode
    ) {
        if (containerFlow != null && containerFlow.stopRequested) {
            return pollContainerRetryStop();
        }
        if (containerStopActive) {
            return TransferResult.pending();
        }
        containers.prepare(transfer.destination());
        Optional<ContainerView> before = containers.observe(transfer.destination());
        if (before.isEmpty()) {
            if (containerObservationTimedOut()) {
                return TransferResult.failed(unavailableCode);
            }
            return TransferResult.pending();
        }
        containerObservationStartedAt = Optional.empty();
        ensureContainerFlow(transfer, before.orElseThrow());
        if (containerStopActive) {
            return TransferResult.pending();
        }
        if (containerFlow.attempt == null) {
            beginContainerAttempt();
        }
        ContainerAttempt attempt = containerFlow.attempt;
        ContainerAttemptResult result = containers.poll(attempt);
        return switch (result.status()) {
            case PENDING -> handlePendingAttempt();
            case ACKNOWLEDGED -> acknowledgeAttempt(attempt, result);
            case REJECTED -> retryOrFail(
                    "CONTAINER_REJECTED",
                    mode == TransferMode.DELIVERY
            );
            case UNAVAILABLE -> retryOrFail(
                    unavailableCode,
                    mode == TransferMode.DELIVERY
            );
        };
    }

    private TransferResult acknowledgeAttempt(
            ContainerAttempt attempt,
            ContainerAttemptResult result
    ) {
        if (!isPlausibleAcknowledgement(attempt, result)) {
            return retryOrFail("CONTAINER_ACK_INVALID", true);
        }
        containerFlow = null;
        return TransferResult.acknowledged();
    }

    private TransferResult handlePendingAttempt() {
        Instant startedAt = containerFlow.attempt.startedAt();
        if (Duration.between(startedAt, clock.now()).compareTo(
                plan.containerAckTimeout()
        ) < 0) {
            return TransferResult.pending();
        }
        return beginContainerStop("CONTAINER_ACK_TIMEOUT", true);
    }

    private TransferResult retryOrFail(
            String failureCode,
            boolean retryCurrentDestination
    ) {
        if (containerFlow.attempt != null) {
            return beginContainerStop(failureCode, retryCurrentDestination);
        }
        if (!retryCurrentDestination
                || containerFlow.attemptsStarted > plan.maxContainerRetries()) {
            return TransferResult.failed(failureCode);
        }
        beginContainerAttempt();
        return TransferResult.pending();
    }

    private TransferResult beginContainerStop(
            String failureCode,
            boolean retryAfterStop
    ) {
        ContainerFlow flow = containerFlow;
        flow.stopRequested = true;
        flow.stopFailureCode = failureCode;
        flow.retryAfterStop = retryAfterStop;
        flow.stopOutcome = containers.requestStop(flow.attempt);
        return pollContainerRetryStop();
    }

    private TransferResult pollContainerRetryStop() {
        ContainerFlow flow = containerFlow;
        if (flow.stopOutcome == StopOutcome.PENDING) {
            flow.stopOutcome = containers.pollStop(flow.attempt);
        }
        if (flow.stopOutcome == StopOutcome.PENDING) {
            return TransferResult.pending();
        }
        if (flow.stopOutcome == StopOutcome.UNSAFE) {
            containerFlow = null;
            return TransferResult.failed(flow.stopFailureCode);
        }
        flow.attempt = null;
        flow.stopRequested = false;
        flow.stopOutcome = StopOutcome.CLEAN;
        if (!flow.retryAfterStop
                || flow.attemptsStarted > plan.maxContainerRetries()) {
            return TransferResult.failed(flow.stopFailureCode);
        }
        beginContainerAttempt();
        return TransferResult.pending();
    }

    private void ensureContainerFlow(ContainerTransfer transfer, ContainerView before) {
        if (containerFlow == null || !containerFlow.transfer.intentKey().equals(
                transfer.intentKey()
        )) {
            abortContainerFlow();
            containerFlow = new ContainerFlow(transfer, before.revision());
        }
    }

    private void beginContainerAttempt() {
        containerFlow.attemptsStarted++;
        containerFlow.attempt = containers.begin(
                containerFlow.transfer,
                containerFlow.baselineRevision
        );
    }

    private boolean isPlausibleAcknowledgement(
            ContainerAttempt attempt,
            ContainerAttemptResult result
    ) {
        if (result.observedRevision() <= attempt.baselineRevision()) {
            return false;
        }
        if (result.destinationAfter().isEmpty()) {
            return false;
        }
        ContainerView destination = result.destinationAfter().orElseThrow();
        return destination.revision() >= result.observedRevision();
    }

    private void enterCurrentDeliveryStage() {
        if (deliveryIndex >= deliveries.size()) {
            enter(PickupStage.CLASSIFY_BUFFER);
            return;
        }
        DeliveryIntent intent = deliveries.get(deliveryIndex);
        if (intent.mode() == DeliveryMode.PACK) {
            enter(PickupStage.PACK);
        } else {
            enter(PickupStage.DELIVER_LOOSE);
        }
    }

    private void beginDeposit() {
        classificationFailures = 0;
        selectedCacheIndex = nearestAvailableCacheIndex();
        abortContainerFlow();
        containerObservationStartedAt = Optional.empty();
        enter(PickupStage.DEPOSIT_BUFFER);
    }

    private void depositCacheFailed(String failureCode, String detail) {
        failedCacheIds.add(selectedCache().id());
        abortContainerFlow();
        clearNavigation();
        containerObservationStartedAt = Optional.empty();
        Optional<Integer> next = nearestAvailableCacheIndexOptional();
        if (next.isPresent()) {
            selectedCacheIndex = next.orElseThrow();
            return;
        }
        pause(failureCode, detail + "; all configured caches failed this round");
    }

    private void retryClassificationOrPause(String failureCode, String detail) {
        if (classificationFailures >= plan.maxContainerRetries()) {
            pause(failureCode, detail + "; classification retry budget exhausted");
            return;
        }
        classificationFailures++;
        abortContainerFlow();
        clearNavigation();
        containerObservationStartedAt = Optional.empty();
    }

    private int nearestAvailableCacheIndex() {
        return nearestAvailableCacheIndexOptional().orElseThrow(() ->
                new IllegalStateException("workflow has no available cache")
        );
    }

    private Optional<Integer> nearestAvailableCacheIndexOptional() {
        BlockPosition position = currentPosition();
        Integer nearestIndex = null;
        long nearestDistance = Long.MAX_VALUE;
        for (int index = 0; index < workflowCaches.size(); index++) {
            ContainerRef cache = workflowCaches.get(index);
            if (failedCacheIds.contains(cache.id())) {
                continue;
            }
            long distance = distanceSquared(position, cache.position());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = index;
            }
        }
        return Optional.ofNullable(nearestIndex);
    }

    private ContainerRef selectedCache() {
        return workflowCaches.get(selectedCacheIndex);
    }

    private Optional<DeliveryIntent> activeDelivery() {
        if (deliveryIndex < 0 || deliveryIndex >= deliveries.size()) {
            return Optional.empty();
        }
        return Optional.of(deliveries.get(deliveryIndex));
    }

    private boolean isOverflow(DeliveryIntent intent) {
        return intent.destination().id().equals(plan.overflowContainer().id());
    }

    private List<GroundItem> observeInputs() {
        List<GroundItem> candidates = List.copyOf(
                worldObserver.observeGroundItems(currentRegion())
        );
        String regionId = currentRegion().id();
        Set<String> targetKeys = regionTargetKeys.get(regionId);
        if (targetKeys == null) {
            Set<String> captured = new HashSet<>();
            for (GroundItem item : candidates) {
                captured.add(item.observationKey());
            }
            targetKeys = Set.copyOf(captured);
            regionTargetKeys.put(regionId, targetKeys);
        }
        List<GroundItem> items = candidates.stream()
                .filter(item -> targetKeys.contains(item.observationKey()))
                .toList();
        for (GroundItem item : items) {
            observedInputs.add(item.observationKey() + ":" + item.itemId() + ":" + item.count());
        }
        if (!items.isEmpty()) {
            regionIdleSince.remove(currentRegion().id());
            workflowIdleSince = Optional.empty();
        } else {
            startRegionIdleTimer();
        }
        return items;
    }

    private boolean collectionTimedOut() {
        String regionId = currentRegion().id();
        Instant deadline = collectionDeadlines.computeIfAbsent(
                regionId,
                ignored -> clock.now().plus(plan.navigationTimeout())
        );
        return !clock.now().isBefore(deadline);
    }

    private void startRegionIdleTimer() {
        regionIdleSince.putIfAbsent(currentRegion().id(), clock.now());
    }

    private List<ItemAmount> inventoryDelta(PlayerInventorySnapshot inventory) {
        List<ItemAmount> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inventory.itemCounts().entrySet()) {
            int baseline = inventoryBaseline.getOrDefault(entry.getKey(), 0);
            int difference = entry.getValue() - baseline;
            if (difference > 0) {
                result.add(new ItemAmount(entry.getKey(), difference));
            }
        }
        return List.copyOf(result);
    }

    private boolean checkPlayerState() {
        WorldObservation world = worldObserver.observeWorld();
        if (!world.connected()) {
            pause("DISCONNECTED", "client is disconnected");
            return false;
        }
        if (!world.alive()) {
            pause("DEAD", "player is not alive");
            return false;
        }
        if (!plan.workflow().dimension().equals(world.dimension())) {
            pause("DIMENSION_MISMATCH", "pickup workflow requires its configured dimension");
            return false;
        }
        if (world.playerPosition().isEmpty()) {
            pause("PLAYER_UNAVAILABLE", "player position is unavailable");
            return false;
        }
        return true;
    }

    private NavigationStatus moveTo(BlockPosition target) {
        return moveTo(target, NavigationTargetType.STANDARD);
    }

    private NavigationStatus moveTo(
            BlockPosition target,
            NavigationTargetType targetType
    ) {
        Instant now = clock.now();
        if (navigationTarget.isEmpty()
                || !navigationTarget.orElseThrow().equals(target)
                || navigationTargetType != targetType) {
            clearNavigation();
            navigationTarget = Optional.of(target);
            navigationTargetType = targetType;
            navigationStartedAt = Optional.of(now);
            return navigation.start(target, targetType);
        }
        NavigationStatus status = navigation.status(target, targetType);
        if (status == NavigationStatus.NAVIGATING) {
            Duration elapsed = Duration.between(navigationStartedAt.orElseThrow(), now);
            if (elapsed.compareTo(plan.navigationTimeout()) >= 0) {
                return NavigationStatus.FAILED;
            }
        }
        return status;
    }

    private void clearNavigation() {
        if (navigationTarget.isPresent()) {
            navigation.cancel();
        }
        navigationTarget = Optional.empty();
        navigationTargetType = NavigationTargetType.STANDARD;
        navigationStartedAt = Optional.empty();
    }

    private BlockPosition currentPosition() {
        return worldObserver.observeWorld().playerPosition().orElseThrow();
    }

    private long distanceSquared(BlockPosition first, BlockPosition second) {
        long deltaX = (long) first.x() - second.x();
        long deltaY = (long) first.y() - second.y();
        long deltaZ = (long) first.z() - second.z();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private PickupRegionBinding currentBinding() {
        return regionOrder.get(currentRegionIndex);
    }

    private PickupRegion currentRegion() {
        return plan.regionsById().get(currentBinding().regionId());
    }

    private void reconcileAfterRecovery() {
        clearNavigation();
        abortContainerFlow();
        deliveries = List.of();
        deliveryIndex = 0;
        if (stage == PickupStage.COMPLETE || stage == PickupStage.FAILED) {
            return;
        }
        if (!checkPlayerState()) {
            return;
        }
        PlayerInventorySnapshot inventory = inventoryAdapter.observe();
        if (!inventoryDelta(inventory).isEmpty()) {
            if (failedCacheIds.contains(selectedCache().id())) {
                Optional<Integer> available = nearestAvailableCacheIndexOptional();
                if (available.isEmpty()) {
                    pause(
                            "BUFFER_UNAVAILABLE",
                            "all configured caches failed this round"
                    );
                    return;
                }
                selectedCacheIndex = available.orElseThrow();
            }
            stage = PickupStage.DEPOSIT_BUFFER;
            return;
        }
        if (stage == PickupStage.WAIT_FOR_IDLE) {
            return;
        }
        if (stage == PickupStage.CLASSIFY_BUFFER
                || stage == PickupStage.DELIVER_LOOSE
                || stage == PickupStage.PACK
                || stage == PickupStage.DEPOSIT_BUFFER) {
            stage = PickupStage.CLASSIFY_BUFFER;
            return;
        }
        stage = PickupStage.NAVIGATE_PICKUP;
    }

    private void restore(AutomationCheckpoint checkpoint) {
        validateCheckpointPlan(checkpoint);
        stage = checkpoint.stage();
        resumeStage = checkpoint.stage();
        observedInputs.addAll(checkpoint.observedInputKeys());
        inventoryBaseline = checkpoint.inventoryBaseline();
        failedCacheIds.addAll(checkpoint.failedCacheIds());
        workflowIdleSince = checkpoint.workflowIdleSince();
        regionIdleSince.putAll(checkpoint.regionIdleSince());
        if (regionIdleSince.isEmpty() && checkpoint.idleSince().isPresent()) {
            regionIdleSince.put(currentRegion().id(), checkpoint.idleSince().orElseThrow());
        }
        round = Math.max(1, checkpoint.round());
        currentRegionIndex = indexOfRegion(checkpoint.currentRegionId());
        selectedCacheIndex = indexOfCache(checkpoint.selectedCacheId());
        if (checkpoint.selectedCacheId().isEmpty()
                && worldObserver.observeWorld().playerPosition().isPresent()) {
            selectedCacheIndex = nearestAvailableCacheIndex();
        }
        classificationCacheIndex = Math.min(
                checkpoint.classificationCacheIndex(),
                workflowCaches.size() - 1
        );
        boolean legacy = checkpoint.currentRegionId().isEmpty()
                && checkpoint.round() == 0;
        classificationAfterRound = checkpoint.classificationAfterRound()
                || legacy && plan.workflow().classificationTiming()
                == ClassificationTiming.AFTER_ROUND;
    }

    private void validateCheckpointPlan(AutomationCheckpoint checkpoint) {
        boolean legacy = checkpoint.currentRegionId().isEmpty()
                && checkpoint.round() == 0;
        if (legacy) {
            return;
        }
        if (checkpoint.workflowId().isPresent()
                && !checkpoint.workflowId().orElseThrow().equals(plan.workflow().id())) {
            throw new IllegalArgumentException("checkpoint workflow does not match plan");
        }
        if (checkpoint.regionOverride().isPresent()
                && !checkpoint.regionOverride().equals(plan.regionOverride())) {
            throw new IllegalArgumentException("checkpoint region override does not match plan");
        }
    }

    private int indexOfRegion(Optional<String> regionId) {
        if (regionId.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < regionOrder.size(); index++) {
            if (regionOrder.get(index).regionId().equals(regionId.orElseThrow())) {
                return index;
            }
        }
        return 0;
    }

    private int indexOfCache(Optional<String> cacheId) {
        if (cacheId.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < workflowCaches.size(); index++) {
            if (workflowCaches.get(index).id().equals(cacheId.orElseThrow())) {
                return index;
            }
        }
        return 0;
    }

    private void enter(PickupStage next) {
        if (next == PickupStage.COMPLETE) {
            requestNavigationStop();
            pollNavigationStop();
            abortContainerFlow();
        }
        stage = next;
        containerObservationStartedAt = Optional.empty();
        if (next != PickupStage.PAUSED && next != PickupStage.FAILED) {
            resumeStage = next;
        }
    }

    private void pause(String code, String detail) {
        if (stage == PickupStage.PAUSED || stage == PickupStage.FAILED) {
            return;
        }
        resumeStage = stage;
        requestNavigationStop();
        pollNavigationStop();
        abortContainerFlow();
        pauseReason = Optional.of(new AutomationPauseReason(code, detail));
        stage = PickupStage.PAUSED;
        saveCheckpoint();
    }

    private void fail(String code, String detail) {
        if (stage == PickupStage.FAILED) {
            return;
        }
        resumeStage = stage;
        requestNavigationStop();
        pollNavigationStop();
        abortContainerFlow();
        pauseReason = Optional.of(new AutomationPauseReason(code, detail));
        stage = PickupStage.FAILED;
    }

    private void abortContainerFlow() {
        if (containerFlow != null && containerFlow.attempt != null) {
            containerStopActive = true;
            stoppingContainerAttempt = containerFlow.attempt;
            containerStopOutcome = containers.requestStop(containerFlow.attempt);
            if (containerStopOutcome == StopOutcome.UNSAFE) {
                containerStopUnconfirmed = true;
            }
        }
        containerFlow = null;
    }

    private void requestNavigationStop() {
        if (navigationTarget.isEmpty()) {
            return;
        }
        navigationStopActive = true;
        navigationStopOutcome = navigation.requestStop();
    }

    private void requestContainerStop() {
        if (containerFlow == null || containerFlow.attempt == null) {
            return;
        }
        containerStopActive = true;
        stoppingContainerAttempt = containerFlow.attempt;
        if (containerFlow.stopRequested) {
            containerStopOutcome = containerFlow.stopOutcome;
            return;
        }
        containerFlow.stopRequested = true;
        containerFlow.stopOutcome = containers.requestStop(containerFlow.attempt);
        containerStopOutcome = containerFlow.stopOutcome;
    }

    private void requestPackingStop() {
        if (packingFlow == null) {
            return;
        }
        packingStopActive = true;
        packingStopOutcome = packing.requestStop(plan.taskId());
    }

    private void pollNavigationStop() {
        if (!navigationStopActive) {
            return;
        }
        if (navigationStopOutcome == StopOutcome.PENDING) {
            navigationStopOutcome = navigation.pollStop();
        }
        if (navigationStopOutcome == StopOutcome.CLEAN) {
            navigationStopActive = false;
            navigationTarget = Optional.empty();
            navigationTargetType = NavigationTargetType.STANDARD;
            navigationStartedAt = Optional.empty();
        }
    }

    private void pollContainerStop() {
        if (!containerStopActive || stoppingContainerAttempt == null) {
            return;
        }
        if (containerStopOutcome == StopOutcome.PENDING) {
            containerStopOutcome = containers.pollStop(stoppingContainerAttempt);
        }
        if (containerStopOutcome == StopOutcome.CLEAN) {
            containerStopActive = false;
            stoppingContainerAttempt = null;
            containerFlow = null;
            containerObservationStartedAt = Optional.empty();
        } else if (containerStopOutcome == StopOutcome.UNSAFE) {
            containerStopUnconfirmed = true;
            containerStopActive = false;
            stoppingContainerAttempt = null;
            containerFlow = null;
            containerObservationStartedAt = Optional.empty();
        }
    }

    private void pollPackingStop() {
        if (!packingStopActive) {
            return;
        }
        if (packingStopOutcome == StopOutcome.PENDING) {
            packingStopOutcome = packing.pollStop(plan.taskId());
        }
        if (packingStopOutcome == StopOutcome.CLEAN) {
            packingStopActive = false;
            packingFlow = null;
        }
    }

    private void updateStopOutcome() {
        if (containerStopUnconfirmed
                || navigationStopActive && navigationStopOutcome == StopOutcome.UNSAFE
                || containerStopActive && containerStopOutcome == StopOutcome.UNSAFE
                || packingStopActive && packingStopOutcome == StopOutcome.UNSAFE) {
            stopOutcome = StopOutcome.UNSAFE;
            finishStop();
            return;
        }
        if (!navigationStopActive && !containerStopActive && !packingStopActive) {
            stopOutcome = StopOutcome.CLEAN;
            finishStop();
        }
    }

    private void finishStop() {
        pauseReason = Optional.of(new AutomationPauseReason(
                stopOutcome == StopOutcome.CLEAN ? "CANCELLED" : "STOP_UNSAFE",
                stopOutcome == StopOutcome.CLEAN
                        ? "pickup task was stopped safely"
                        : "pickup task stop could not be confirmed"
        ));
        stage = PickupStage.FAILED;
    }

    private boolean containerObservationTimedOut() {
        Instant now = clock.now();
        if (containerObservationStartedAt.isEmpty()) {
            containerObservationStartedAt = Optional.of(now);
            return false;
        }
        Duration elapsed = Duration.between(containerObservationStartedAt.orElseThrow(), now);
        return elapsed.compareTo(plan.containerAckTimeout()) >= 0;
    }

    private void saveCheckpoint() {
        PickupStage checkpointStage = stage == PickupStage.PAUSED ? resumeStage : stage;
        if (checkpointStage == PickupStage.FAILED) {
            return;
        }
        checkpoints.save(new AutomationCheckpoint(
                plan.taskId(),
                checkpointStage,
                Optional.of(plan.workflow().id()),
                plan.regionOverride(),
                Optional.of(currentRegion().id()),
                round,
                failedCacheIds,
                workflowIdleSince,
                regionIdleSince,
                observedInputs,
                inventoryBaseline,
                Optional.of(selectedCache().id()),
                classificationCacheIndex,
                classificationAfterRound
        ));
    }

    private record TransferResult(TransferStatus status, String failureCode) {
        private static TransferResult acknowledged() {
            return new TransferResult(TransferStatus.ACKNOWLEDGED, "");
        }

        private static TransferResult pending() {
            return new TransferResult(TransferStatus.PENDING, "");
        }

        private static TransferResult failed(String failureCode) {
            return new TransferResult(TransferStatus.FAILED, failureCode);
        }
    }

    private record PackingFlow(PackingRequest request) {
        private PackingFlow {
            Objects.requireNonNull(request, "request");
        }
    }

    private enum TransferStatus {
        ACKNOWLEDGED,
        PENDING,
        FAILED
    }

    private enum TransferMode {
        DEPOSIT_CACHE,
        DELIVERY
    }

    private static final class ContainerFlow {
        private final ContainerTransfer transfer;
        private final long baselineRevision;
        private int attemptsStarted;
        private ContainerAttempt attempt;
        private boolean stopRequested;
        private StopOutcome stopOutcome = StopOutcome.CLEAN;
        private String stopFailureCode = "";
        private boolean retryAfterStop;

        private ContainerFlow(ContainerTransfer transfer, long baselineRevision) {
            this.transfer = transfer;
            this.baselineRevision = baselineRevision;
        }
    }
}
