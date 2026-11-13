package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates a recoverable pickup-to-storage workflow.
 *
 * <p>The engine persists stages and high-level intents only. Every transfer is
 * restarted with a fresh attempt after recovery, and no unmatched item is ever
 * dropped.
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

    private final Set<String> observedInputs = new HashSet<>();
    private Map<String, Integer> inventoryBaseline = Map.of();
    private Optional<Instant> idleSince = Optional.empty();
    private List<DeliveryIntent> deliveries = List.of();
    private int deliveryIndex;
    private ContainerFlow containerFlow;
    private PickupStage stage = PickupStage.NAVIGATE_PICKUP;
    private PickupStage resumeStage = PickupStage.NAVIGATE_PICKUP;
    private Optional<AutomationPauseReason> pauseReason = Optional.empty();
    private Optional<BlockPosition> navigationTarget = Optional.empty();
    private Optional<Instant> navigationStartedAt = Optional.empty();
    private Optional<Instant> containerObservationStartedAt = Optional.empty();
    private boolean started;
    private boolean recovered;

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
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Optional<AutomationCheckpoint> stored = checkpoints.load(plan.taskId());
        if (stored.isPresent()) {
            restore(stored.orElseThrow());
            recovered = true;
            reconcileAfterRecovery();
        } else {
            inventoryBaseline = inventoryAdapter.observe().itemCounts();
        }
        saveCheckpoint();
    }

    public synchronized AutomationSnapshot tick() {
        start();
        if (stage == PickupStage.COMPLETE
                || stage == PickupStage.FAILED
                || stage == PickupStage.PAUSED) {
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
        reconcileAfterRecovery();
        saveCheckpoint();
    }

    public synchronized void pauseForControl() {
        pause("CONTROL_PAUSED", "automation paused by the control surface");
    }

    public synchronized void cancel() {
        if (stage == PickupStage.COMPLETE || stage == PickupStage.FAILED) {
            return;
        }
        clearNavigation();
        abortContainerFlow();
        pauseReason = Optional.of(new AutomationPauseReason(
                "CANCELLED",
                "pickup task was cancelled"
        ));
        stage = PickupStage.FAILED;
    }

    public synchronized AutomationSnapshot snapshot() {
        return new AutomationSnapshot(
                plan.taskId(),
                stage,
                pauseReason,
                stage == PickupStage.PAUSED ? Optional.of(resumeStage) : Optional.empty(),
                activeDelivery(),
                observedInputs.size(),
                Math.max(0, deliveries.size() - deliveryIndex)
        );
    }

    private void tickNavigatePickup() {
        NavigationStatus status = moveTo(plan.pickupSite().center());
        if (status == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to pickup site failed");
            return;
        }
        if (status == NavigationStatus.ARRIVED) {
            clearNavigation();
            enter(PickupStage.COLLECT);
        }
    }

    private void tickCollect() {
        List<GroundItem> items = observeInputs();
        PlayerInventorySnapshot inventory = inventoryAdapter.observe();
        if (inventory.freeSlots() <= plan.inventoryReserveSlots()) {
            clearNavigation();
            enter(PickupStage.DEPOSIT_BUFFER);
            return;
        }
        if (items.isEmpty()) {
            clearNavigation();
            if (!inventoryDelta(inventory).isEmpty()) {
                enter(PickupStage.DEPOSIT_BUFFER);
            } else {
                startIdleTimerIfNeeded();
                enter(PickupStage.WAIT_FOR_IDLE);
            }
            return;
        }
        BlockPosition origin = currentPosition();
        GroundItem nearest = items.getFirst();
        int nearestDistance = distanceSquared(origin, nearest.position());
        for (GroundItem item : items) {
            int distance = distanceSquared(origin, item.position());
            if (distance < nearestDistance) {
                nearest = item;
                nearestDistance = distance;
            }
        }
        NavigationStatus status = moveTo(nearest.position());
        if (status == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to a ground item failed");
        }
    }

    private void tickDepositBuffer() {
        NavigationStatus travel = moveTo(plan.pickupSite().cacheContainer().position());
        if (travel == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to pickup buffer failed");
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
            startIdleTimerIfNeeded();
            enter(PickupStage.WAIT_FOR_IDLE);
            return;
        }
        ContainerTransfer transfer = new ContainerTransfer(
                plan.taskId() + ":deposit-buffer",
                Optional.empty(),
                plan.pickupSite().cacheContainer(),
                collected
        );
        if (transfer(transfer, "BUFFER_UNAVAILABLE")) {
            inventoryBaseline = inventoryAdapter.observe().itemCounts();
            startIdleTimerIfNeeded();
            enter(PickupStage.WAIT_FOR_IDLE);
        }
    }

    private void tickWaitForIdle() {
        List<GroundItem> items = observeInputs();
        if (!items.isEmpty()) {
            enter(PickupStage.COLLECT);
            return;
        }
        PlayerInventorySnapshot inventory = inventoryAdapter.observe();
        if (!inventoryDelta(inventory).isEmpty()) {
            enter(PickupStage.DEPOSIT_BUFFER);
            return;
        }
        startIdleTimerIfNeeded();
        Duration idle = Duration.between(idleSince.orElseThrow(), clock.now());
        if (idle.compareTo(plan.pickupSite().idleDelay()) >= 0) {
            enter(PickupStage.CLASSIFY_BUFFER);
        }
    }

    private void tickClassifyBuffer() {
        ContainerRef buffer = plan.pickupSite().cacheContainer();
        NavigationStatus travel = moveTo(buffer.position());
        if (travel == NavigationStatus.FAILED) {
            pause("PATH_FAILED", "navigation to buffer for classification failed");
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
                pause("BUFFER_UNAVAILABLE", "pickup buffer could not be observed");
            }
            return;
        }
        containerObservationStartedAt = Optional.empty();
        if (view.orElseThrow().isEmpty()) {
            enter(PickupStage.COMPLETE);
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
        if (transfer(intent.asTransfer(), failureCode)) {
            deliveryIndex++;
            enterCurrentDeliveryStage();
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
        if (packing.submit(request) == PackingDisposition.ACCEPTED) {
            deliveryIndex++;
            enterCurrentDeliveryStage();
        } else {
            pause("PACKING_UNAVAILABLE", "packing station rejected the delivery");
        }
    }

    private boolean transfer(ContainerTransfer transfer, String unavailableCode) {
        containers.prepare(transfer.destination());
        Optional<ContainerView> before = containers.observe(transfer.destination());
        if (before.isEmpty()) {
            if (containerObservationTimedOut()) {
                pause(unavailableCode, "destination container is unavailable");
            }
            return false;
        }
        containerObservationStartedAt = Optional.empty();
        ensureContainerFlow(transfer, before.orElseThrow());
        if (containerFlow.attempt == null) {
            beginContainerAttempt();
        }
        ContainerAttempt attempt = containerFlow.attempt;
        ContainerAttemptResult result = containers.poll(attempt);
        switch (result.status()) {
            case PENDING -> {
                return handlePendingAttempt(unavailableCode);
            }
            case ACKNOWLEDGED -> {
                if (!isPlausibleAcknowledgement(attempt, result)) {
                    return retryOrPause("CONTAINER_ACK_INVALID");
                }
                containerFlow = null;
                return true;
            }
            case REJECTED -> {
                return retryOrPause("CONTAINER_REJECTED");
            }
            case UNAVAILABLE -> {
                return retryOrPause(unavailableCode);
            }
            default -> throw new IllegalStateException("unknown attempt result");
        }
    }

    private boolean handlePendingAttempt(String unavailableCode) {
        Instant startedAt = containerFlow.attempt.startedAt();
        if (Duration.between(startedAt, clock.now()).compareTo(
                plan.containerAckTimeout()
        ) < 0) {
            return false;
        }
        containers.cancel(containerFlow.attempt);
        containerFlow.attempt = null;
        return retryOrPause("CONTAINER_ACK_TIMEOUT");
    }

    private boolean retryOrPause(String failureCode) {
        if (containerFlow.attempt != null) {
            containers.cancel(containerFlow.attempt);
            containerFlow.attempt = null;
        }
        if (containerFlow.attemptsStarted > plan.maxContainerRetries()) {
            pause(failureCode, "container transaction exhausted retry budget");
            return false;
        }
        beginContainerAttempt();
        return false;
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
            enter(PickupStage.COMPLETE);
            return;
        }
        DeliveryIntent intent = deliveries.get(deliveryIndex);
        if (intent.mode() == DeliveryMode.PACK) {
            enter(PickupStage.PACK);
        } else {
            enter(PickupStage.DELIVER_LOOSE);
        }
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
        List<GroundItem> items = List.copyOf(worldObserver.observeGroundItems(
                plan.pickupSite()
        ));
        boolean discovered = false;
        for (GroundItem item : items) {
            String token = item.observationKey() + ":" + item.itemId() + ":" + item.count();
            if (observedInputs.add(token)) {
                discovered = true;
            }
        }
        if (discovered) {
            idleSince = Optional.of(clock.now());
        } else if (items.isEmpty()) {
            startIdleTimerIfNeeded();
        }
        return items;
    }

    private void startIdleTimerIfNeeded() {
        if (idleSince.isEmpty()) {
            idleSince = Optional.of(clock.now());
        }
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
        if (!plan.pickupSite().dimension().equals(world.dimension())) {
            pause("DIMENSION_MISMATCH", "pickup task requires its configured dimension");
            return false;
        }
        if (world.playerPosition().isEmpty()) {
            pause("PLAYER_UNAVAILABLE", "player position is unavailable");
            return false;
        }
        return true;
    }

    private NavigationStatus moveTo(BlockPosition target) {
        Instant now = clock.now();
        if (navigationTarget.isEmpty() || !navigationTarget.orElseThrow().equals(target)) {
            clearNavigation();
            navigationTarget = Optional.of(target);
            navigationStartedAt = Optional.of(now);
            return navigation.start(target);
        }
        NavigationStatus status = navigation.status(target);
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
        navigationStartedAt = Optional.empty();
    }

    private BlockPosition currentPosition() {
        return worldObserver.observeWorld().playerPosition().orElseThrow();
    }

    private int distanceSquared(BlockPosition first, BlockPosition second) {
        int deltaX = first.x() - second.x();
        int deltaY = first.y() - second.y();
        int deltaZ = first.z() - second.z();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
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
            stage = PickupStage.DEPOSIT_BUFFER;
            return;
        }
        Optional<ContainerView> buffer = containers.observe(plan.pickupSite().cacheContainer());
        if (buffer.isEmpty()) {
            pause("RECOVERY_BUFFER_UNAVAILABLE", "buffer unavailable during recovery");
            return;
        }
        ContainerView view = buffer.orElseThrow();
        if (!view.isEmpty()) {
            if (stage == PickupStage.WAIT_FOR_IDLE && idleSince.isPresent()) {
                return;
            }
            stage = PickupStage.CLASSIFY_BUFFER;
            return;
        }
        if (stage == PickupStage.WAIT_FOR_IDLE) {
            return;
        }
        if (stage == PickupStage.CLASSIFY_BUFFER
                || stage == PickupStage.DELIVER_LOOSE
                || stage == PickupStage.PACK) {
            stage = PickupStage.COMPLETE;
            return;
        }
        stage = PickupStage.NAVIGATE_PICKUP;
    }

    private void restore(AutomationCheckpoint checkpoint) {
        stage = checkpoint.stage();
        resumeStage = checkpoint.stage();
        observedInputs.addAll(checkpoint.observedInputKeys());
        idleSince = checkpoint.idleSince();
        inventoryBaseline = checkpoint.inventoryBaseline();
    }

    private void enter(PickupStage next) {
        if (next == PickupStage.COMPLETE) {
            clearNavigation();
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
        clearNavigation();
        abortContainerFlow();
        pauseReason = Optional.of(new AutomationPauseReason(code, detail));
        stage = PickupStage.PAUSED;
        saveCheckpoint();
    }

    private void abortContainerFlow() {
        if (containerFlow != null && containerFlow.attempt != null) {
            containers.cancel(containerFlow.attempt);
        }
        containerFlow = null;
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
                Optional.of(plan.pickupSite().id()),
                observedInputs,
                idleSince,
                inventoryBaseline
        ));
    }

    private static final class ContainerFlow {
        private final ContainerTransfer transfer;
        private final long baselineRevision;
        private int attemptsStarted;
        private ContainerAttempt attempt;

        private ContainerFlow(ContainerTransfer transfer, long baselineRevision) {
            this.transfer = transfer;
            this.baselineRevision = baselineRevision;
        }
    }
}
