package dev.stowling.automation;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
import dev.stowling.packing.PackingReleaseMode;
import dev.stowling.packing.PackingRunResult;
import dev.stowling.packing.PackingStationCandidate;
import dev.stowling.packing.PackingStationException;
import dev.stowling.packing.PackingStationPool;
import dev.stowling.packing.PackingStationTickPort;
import dev.stowling.packing.PackingWorkcell;
import dev.stowling.packing.PackingWorkcellOrchestrator;
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tick-driven adapter between pickup automation and the packing workcell rules.
 */
public final class TickPackingPort implements PackingPort {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_TRANSFER_ATTEMPTS = 3;

    private final Supplier<StowlingConfig> configSupplier;
    private final WorldObserver worldObserver;
    private final NavigationPort navigation;
    private final ContainerInteraction containers;
    private final PackingStationTickPort stationPort;
    private final AutomationClock clock;
    private final PackingWorkcellOrchestrator orchestrator =
            new PackingWorkcellOrchestrator(new PackingStationPool());
    private final Map<UUID, TaskState> tasks = new HashMap<>();
    private final Map<UUID, StopState> stops = new HashMap<>();
    private final Map<UUID, StopOutcome> stopOutcomes = new HashMap<>();

    public TickPackingPort(
            Supplier<StowlingConfig> configSupplier,
            WorldObserver worldObserver,
            NavigationPort navigation,
            ContainerInteraction containers,
            PackingStationTickPort stationPort,
            AutomationClock clock
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.worldObserver = Objects.requireNonNull(worldObserver, "worldObserver");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.containers = Objects.requireNonNull(containers, "containers");
        this.stationPort = Objects.requireNonNull(stationPort, "stationPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized PackingDisposition submit(dev.stowling.automation.PackingRequest request) {
        Objects.requireNonNull(request, "request");
        TaskState state = tasks.get(request.taskId());
        if (state == null) {
            Optional<TaskState> created = createState(request);
            if (created.isEmpty()) {
                return PackingDisposition.UNAVAILABLE;
            }
            state = created.orElseThrow();
            tasks.put(request.taskId(), state);
            stopOutcomes.remove(request.taskId());
        } else if (!state.request.equals(request)) {
            return PackingDisposition.UNAVAILABLE;
        }
        PackingDisposition disposition = state.completed ? PackingDisposition.ACCEPTED
                : state.tick();
        if (request.taskPlan().isPresent() && disposition == PackingDisposition.ACCEPTED) {
            state.completed = true;
            StopOutcome outcome = requestStop(request.taskId());
            return switch (outcome) {
                case CLEAN -> PackingDisposition.ACCEPTED;
                case PENDING -> PackingDisposition.PENDING;
                case UNSAFE -> PackingDisposition.UNAVAILABLE;
            };
        }
        if (disposition != PackingDisposition.PENDING) {
            tasks.remove(request.taskId());
            rememberStopOutcome(
                    request.taskId(),
                    disposition == PackingDisposition.ACCEPTED
                            ? StopOutcome.CLEAN
                            : StopOutcome.UNSAFE
            );
        }
        return disposition;
    }

    @Override
    public synchronized StopOutcome requestStop(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        StopState stop = stops.get(taskId);
        if (stop != null) {
            return pollStop(taskId);
        }
        StopOutcome previous = stopOutcomes.get(taskId);
        if (previous != null) {
            return previous;
        }
        TaskState task = tasks.get(taskId);
        if (task == null) {
            return StopOutcome.UNSAFE;
        }
        stop = new StopState(task);
        stops.put(taskId, stop);
        stop.navigationOutcome = navigation.requestStop();
        stop.navigationActive = stop.navigationOutcome == StopOutcome.PENDING;
        for (ContainerAttempt attempt : stop.transfers) {
            stop.containerOutcomes.put(
                    attempt.attemptId(),
                    containers.requestStop(attempt)
            );
        }
        stop.refreshContainerOutcome();
        return pollStop(taskId);
    }

    @Override
    public synchronized StopOutcome pollStop(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        StopState stop = stops.get(taskId);
        if (stop == null) {
            return stopOutcomes.getOrDefault(taskId, StopOutcome.UNSAFE);
        }
        if (stop.navigationActive) {
            stop.navigationOutcome = navigation.pollStop();
            stop.navigationActive = stop.navigationOutcome == StopOutcome.PENDING;
        }
        if (stop.containersActive) {
            for (ContainerAttempt attempt : stop.transfers) {
                if (stop.containerOutcomes.get(attempt.attemptId())
                        == StopOutcome.PENDING) {
                    stop.containerOutcomes.put(
                            attempt.attemptId(),
                            containers.pollStop(attempt)
                    );
                }
            }
            stop.refreshContainerOutcome();
        }
        if (stop.navigationOutcome == StopOutcome.UNSAFE
                || stop.containerOutcome == StopOutcome.UNSAFE) {
            removeTask(taskId, StopOutcome.UNSAFE);
            return StopOutcome.UNSAFE;
        }
        if (stop.navigationActive || stop.containersActive) {
            return StopOutcome.PENDING;
        }
        if (!stop.closeAttempted) {
            stop.closeAttempted = true;
            for (PackingWorkcell workcell : stop.workcells) {
                try {
                    stationPort.close(workcell);
                } catch (PackingStationException exception) {
                    removeTask(taskId, StopOutcome.UNSAFE);
                    return StopOutcome.UNSAFE;
                }
            }
        }
        removeTask(taskId, StopOutcome.CLEAN);
        return StopOutcome.CLEAN;
    }

    private void removeTask(UUID taskId, StopOutcome outcome) {
        tasks.remove(taskId);
        stops.remove(taskId);
        rememberStopOutcome(taskId, outcome);
    }

    private void rememberStopOutcome(UUID taskId, StopOutcome outcome) {
        stopOutcomes.put(taskId, outcome);
        if (stopOutcomes.size() > 128) {
            UUID oldest = stopOutcomes.keySet().iterator().next();
            stopOutcomes.remove(oldest);
        }
    }

    private static StopOutcome combine(StopOutcome first, StopOutcome second) {
        if (first == StopOutcome.UNSAFE || second == StopOutcome.UNSAFE) {
            return StopOutcome.UNSAFE;
        }
        if (first == StopOutcome.PENDING || second == StopOutcome.PENDING) {
            return StopOutcome.PENDING;
        }
        return StopOutcome.CLEAN;
    }

    private Optional<TaskState> createState(PackingRequest request) {
        if (request.taskPlan().isPresent()) {
            TaskPackingPlan plan = request.taskPlan().orElseThrow();
            return Optional.of(new TaskState(
                    request,
                    plan.route(),
                    plan.stations()
            ));
        }
        StowlingConfig config = configSupplier.get();
        Optional<StorageRoute> route = config.storageRoutes().stream()
                .filter(candidate -> candidate.id().equals(request.routeId()))
                .findFirst();
        if (route.isEmpty() || !route.orElseThrow().packingEnabled()) {
            return Optional.empty();
        }
        List<PackingStation> stations = new ArrayList<>();
        for (String stationId : request.stationIds()) {
            Optional<PackingStation> station = config.packingStations().stream()
                    .filter(candidate -> candidate.id().equals(stationId))
                    .findFirst();
            if (station.isPresent()) {
                stations.add(station.orElseThrow());
            }
        }
        if (stations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TaskState(request, route.orElseThrow(), stations));
    }

    private final class TaskState {
        private final dev.stowling.automation.PackingRequest request;
        private final StorageRoute route;
        private final List<PackingStation> stations;
        private final List<ItemState> items;
        private int itemIndex;
        private boolean completed;

        private TaskState(
                PackingRequest request,
                StorageRoute route,
                List<PackingStation> stations
        ) {
            this.request = request;
            this.route = route;
            this.stations = List.copyOf(stations);
            this.items = request.items().stream()
                    .map(amount -> new ItemState(amount, stations, request))
                    .toList();
        }

        private PackingDisposition tick() {
            if (!worldObserver.observeWorld().connected()) {
                return PackingDisposition.PENDING;
            }
            if (itemIndex >= items.size()) {
                return PackingDisposition.ACCEPTED;
            }
            ItemState item = items.get(itemIndex);
            PackingDisposition disposition = tickItem(item);
            if (disposition == PackingDisposition.ACCEPTED) {
                itemIndex++;
                return itemIndex >= items.size()
                        ? PackingDisposition.ACCEPTED
                        : PackingDisposition.PENDING;
            }
            return disposition;
        }

        private PackingDisposition tickItem(ItemState item) {
            return switch (item.phase) {
                case PREPARE -> prepareItem(item);
                case LOOSE -> tickTransfer(item, TransferRole.LOOSE);
                case PRODUCT_SNAPSHOT -> takeProductSnapshot(item);
                case STATION -> tickStation(item);
                case AWAIT_PRODUCT -> tickAwaitProduct(item);
                case TRANSFER_BOX -> tickTransfer(item, TransferRole.PACKED_BOX);
                case OVERFLOW -> tickTransfer(item, TransferRole.OVERFLOW);
                case DONE -> PackingDisposition.ACCEPTED;
                case UNAVAILABLE -> PackingDisposition.UNAVAILABLE;
            };
        }

        private PackingDisposition prepareItem(ItemState item) {
            if (!arriveAt(route.bulkContainer().position())) {
                return waitOrUnavailable(item);
            }
            Optional<ContainerView> view = containers.observe(route.bulkContainer());
            if (view.isEmpty()) {
                return waitOrUnavailable(item);
            }
            int current = view.orElseThrow().itemCounts()
                    .getOrDefault(item.itemId, 0);
            int threshold = looseThreshold(view.orElseThrow());
            item.remainingLoose = Math.min(item.remaining, Math.max(0, threshold - current));
            item.remainingPack = item.remaining - item.remainingLoose;
            item.phase = item.remainingLoose > 0 ? Phase.LOOSE : Phase.PRODUCT_SNAPSHOT;
            item.phaseStarted = clock.now();
            return PackingDisposition.PENDING;
        }

        private int looseThreshold(ContainerView bulk) {
            int threshold = route.packingThreshold() == null ? 0 : route.packingThreshold();
            if (route.packingThresholdMode() == PackingTrigger.ON_ITEM_COUNT) {
                return threshold;
            }
            long capacity = (long) bulk.slotCapacity() * 64L;
            return (int) Math.min(Integer.MAX_VALUE, (capacity * threshold + 99L) / 100L);
        }

        private PackingDisposition tickTransfer(ItemState item, TransferRole role) {
            if (item.transfer == null) {
                Optional<ContainerRef> source = role == TransferRole.PACKED_BOX
                        ? Optional.of(item.packedBoxSource)
                        : Optional.of(request.buffer());
                ContainerRef destination = role == TransferRole.OVERFLOW
                        ? item.overflowContainer()
                        : route.bulkContainer();
                TransferFlow flow = beginTransfer(
                        source,
                        destination,
                        item.transferItemId(role),
                        item.transferQuantity(role)
                );
                if (flow == null) {
                    return waitOrUnavailable(item);
                }
                item.transfer = flow;
                return PackingDisposition.PENDING;
            }
            ContainerAttemptResult result = containers.poll(item.transfer.attempt);
            if (result.status() == ContainerAttemptStatus.PENDING) {
                return waitOrUnavailable(item);
            }
            if (result.status() != ContainerAttemptStatus.ACKNOWLEDGED
                    || !confirmedQuantity(item.transfer, result)) {
                item.transfer = null;
                item.transferAttempts++;
                if (item.transferAttempts < MAX_TRANSFER_ATTEMPTS) {
                    item.phaseStarted = clock.now();
                    return PackingDisposition.PENDING;
                }
                item.phase = role == TransferRole.OVERFLOW
                        ? Phase.UNAVAILABLE
                        : Phase.OVERFLOW;
                return item.phase == Phase.UNAVAILABLE
                        ? PackingDisposition.UNAVAILABLE
                        : PackingDisposition.PENDING;
            }
            int quantity = item.transfer.quantity;
            item.transfer = null;
            item.transferAttempts = 0;
            item.phaseStarted = clock.now();
            switch (role) {
                case LOOSE -> {
                    item.remainingLoose -= quantity;
                    if (item.remainingLoose == 0) {
                        item.phase = item.remainingPack > 0
                                ? Phase.PRODUCT_SNAPSHOT
                                : Phase.DONE;
                    }
                }
                case PACKED_BOX -> {
                    stationPort.clearAwaitingPackedBox(item.workcell());
                    item.phase = item.remainingPack > 0 ? Phase.STATION : Phase.DONE;
                }
                case OVERFLOW -> {
                    item.remainingPack -= quantity;
                    if (item.remainingPack == 0) {
                        item.phase = Phase.DONE;
                    }
                }
            }
            return PackingDisposition.PENDING;
        }

        private PackingDisposition takeProductSnapshot(ItemState item) {
            ContainerRef product = item.station().productContainer();
            if (!arriveAt(product.position())) {
                return waitOrUnavailable(item);
            }
            Optional<ContainerView> view = containers.observe(product);
            if (view.isEmpty()) {
                return waitOrUnavailable(item);
            }
            item.productBefore = view.orElseThrow().itemCounts();
            item.phase = Phase.STATION;
            item.phaseStarted = clock.now();
            return PackingDisposition.PENDING;
        }

        private PackingDisposition tickStation(ItemState item) {
            if (item.stationIndex >= stations.size()) {
                item.phase = Phase.OVERFLOW;
                return PackingDisposition.PENDING;
            }
            PackingWorkcell workcell = item.workcell();
            if (stationPort.awaitingPackedBox(workcell)) {
                item.phase = Phase.AWAIT_PRODUCT;
                item.phaseStarted = clock.now();
                return PackingDisposition.PENDING;
            }
            if (!arriveAt(workcell.station().shulkerBoxPosition())) {
                return waitOrUnavailable(item);
            }
            stationPort.setInsertionSource(workcell, Optional.of(request.buffer()));
            try {
                if (!stationPort.prepare(workcell)) {
                    if (timedOut(item)) {
                        return failStation(item);
                    }
                    return PackingDisposition.PENDING;
                }
            } catch (PackingStationException exception) {
                return failStation(item);
            }
            int confirmed = stationPort.takeConfirmedInsertion(workcell);
            if (confirmed > 0) {
                consumePacked(item, confirmed);
                item.phaseStarted = clock.now();
                if (item.remainingPack == 0) {
                    try {
                        if (!workcell.threshold().reached(stationPort.inspect(workcell))) {
                            closeStation(workcell);
                            item.phase = Phase.DONE;
                            return PackingDisposition.PENDING;
                        }
                    } catch (PackingStationException exception) {
                        return failStation(item);
                    }
                }
            }
            PackingRunResult result = orchestrator.pack(
                    createPackingRequest(item, workcell),
                    List.of(item.candidate()),
                    stationPort
            );
            consumePacked(item, result.insertedQuantity());
            switch (result.status()) {
                case FULL_BOX_READY -> {
                    result.transferIntent().ifPresent(intent -> item.packedBoxSource =
                            intent.source());
                    item.phase = Phase.AWAIT_PRODUCT;
                    item.phaseStarted = clock.now();
                }
                case PARTIAL_BOX -> {
                    closeStation(workcell);
                    item.phase = item.remainingPack == 0 ? Phase.DONE : Phase.STATION;
                }
                case FAILED_RETAINED, OVERFLOW -> {
                    if (timedOut(item)) {
                        return failStation(item);
                    }
                }
            }
            return PackingDisposition.PENDING;
        }

        private dev.stowling.packing.PackingRequest createPackingRequest(
                ItemState item,
                PackingWorkcell workcell
        ) {
            String requestId = request.taskId() + ":" + item.itemId;
            String dimension = workcell.station().dimension();
            int quantity = Math.max(1, item.remainingPack);
            return new dev.stowling.packing.PackingRequest(
                    requestId,
                    dimension,
                    item.itemId,
                    quantity,
                    route,
                    item.overflowContainer()
            );
        }

        private PackingDisposition tickAwaitProduct(ItemState item) {
            PackingWorkcell workcell = item.workcell();
            ContainerRef product = workcell.station().productContainer();
            if (!arriveAt(product.position())) {
                return waitOrUnavailable(item);
            }
            Optional<ContainerView> view = containers.observe(product);
            if (view.isPresent()) {
                Optional<String> shulker = newShulker(view.orElseThrow(), item.productBefore);
                if (shulker.isPresent()) {
                    item.packedBoxItemId = shulker.orElseThrow();
                    item.packedBoxSource = product;
                    item.phase = Phase.TRANSFER_BOX;
                    item.phaseStarted = clock.now();
                    return PackingDisposition.PENDING;
                }
            }
            return waitOrUnavailable(item);
        }

        private void consumePacked(ItemState item, int quantity) {
            if (quantity > 0) {
                item.remainingPack = Math.max(0, item.remainingPack - quantity);
            }
        }

        private PackingDisposition failStation(ItemState item) {
            item.stationIndex++;
            item.phaseStarted = clock.now();
            item.transferAttempts = 0;
            if (item.stationIndex >= stations.size()) {
                item.phase = item.remainingPack > 0 ? Phase.OVERFLOW : Phase.DONE;
            } else {
                item.phase = Phase.PRODUCT_SNAPSHOT;
            }
            return PackingDisposition.PENDING;
        }

        private void closeStation(PackingWorkcell workcell) {
            try {
                stationPort.close(workcell);
            } catch (PackingStationException ignored) {
                // A closed screen is the desired postcondition.
            }
        }

        private PackingDisposition waitOrUnavailable(ItemState item) {
            return timedOut(item)
                    ? unavailable(item)
                    : PackingDisposition.PENDING;
        }

        private PackingDisposition unavailable(ItemState item) {
            item.phase = Phase.UNAVAILABLE;
            return PackingDisposition.UNAVAILABLE;
        }

        private boolean timedOut(ItemState item) {
            return Duration.between(item.phaseStarted, clock.now())
                    .compareTo(WAIT_TIMEOUT) >= 0;
        }

    }

    private TransferFlow beginTransfer(
            Optional<ContainerRef> source,
            ContainerRef destination,
            String itemId,
            int quantity
    ) {
        if (!arriveAt(destination.position())) {
            return null;
        }
        Optional<ContainerView> view = containers.observe(destination);
        if (view.isEmpty()) {
            return null;
        }
        ContainerTransfer transfer = new ContainerTransfer(
                "packing:" + destination.id() + ":" + itemId,
                source,
                destination,
                List.of(new ItemAmount(itemId, quantity))
        );
        ContainerView baseline = view.orElseThrow();
        return new TransferFlow(
                containers.begin(transfer, baseline.revision()),
                itemId,
                quantity,
                baseline.itemCounts().getOrDefault(itemId, 0)
        );
    }

    private boolean confirmedQuantity(TransferFlow flow, ContainerAttemptResult result) {
        if (result.destinationAfter().isEmpty()) {
            return false;
        }
        int after = result.destinationAfter().orElseThrow().itemCounts()
                .getOrDefault(flow.itemId, 0);
        return after >= Math.addExact(flow.beforeCount, flow.quantity);
    }

    private boolean arriveAt(BlockPosition target) {
        WorldObservation world = worldObserver.observeWorld();
        if (!world.connected() || !world.alive() || world.playerPosition().isEmpty()) {
            return false;
        }
        NavigationStatus status = navigation.start(target);
        if (status == NavigationStatus.ARRIVED) {
            return true;
        }
        if (status == NavigationStatus.FAILED) {
            navigation.cancel();
            return false;
        }
        return navigation.status(target) == NavigationStatus.ARRIVED;
    }

    private Optional<String> newShulker(
            ContainerView product,
            Map<String, Integer> before
    ) {
        return product.itemCounts().entrySet().stream()
                .filter(entry -> isShulker(entry.getKey()))
                .filter(entry -> entry.getValue() > before.getOrDefault(entry.getKey(), 0))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private boolean isShulker(String itemId) {
        return itemId.equals("minecraft:shulker_box") || itemId.endsWith("_shulker_box");
    }

    private final class ItemState {
        private final String itemId;
        private final int remaining;
        private final List<PackingStation> stationCandidates;
        private final Optional<ContainerRef> taskOverflowContainer;
        private Phase phase = Phase.PREPARE;
        private Instant phaseStarted = clock.now();
        private int remainingLoose;
        private int remainingPack;
        private int stationIndex;
        private int transferAttempts;
        private TransferFlow transfer;
        private Map<String, Integer> productBefore = Map.of();
        private ContainerRef packedBoxSource;
        private String packedBoxItemId;

        private ItemState(
                ItemAmount amount,
                List<PackingStation> stationCandidates,
                PackingRequest request
        ) {
            itemId = amount.itemId();
            remaining = amount.count();
            this.stationCandidates = List.copyOf(stationCandidates);
            this.taskOverflowContainer = request.taskPlan()
                    .map(TaskPackingPlan::overflowContainer);
        }

        private PackingWorkcell workcell() {
            PackingStation station = stationCandidates.get(stationIndex);
            return new PackingWorkcell(station, PackingReleaseMode.AUTO_ON_CLOSE);
        }

        private PackingStation station() {
            return stationCandidates.get(Math.min(
                    stationIndex,
                    stationCandidates.size() - 1
            ));
        }

        private PackingStationCandidate candidate() {
            PackingWorkcell workcell = workcell();
            BlockPosition player = worldObserver.observeWorld().playerPosition()
                    .orElse(workcell.station().shulkerBoxPosition());
            BlockPosition box = workcell.station().shulkerBoxPosition();
            long deltaX = player.x() - box.x();
            long deltaY = player.y() - box.y();
            long deltaZ = player.z() - box.z();
            long distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            return new PackingStationCandidate(workcell, true, true, distance);
        }

        private String transferItemId(TransferRole role) {
            return role == TransferRole.PACKED_BOX ? packedBoxItemId : itemId;
        }

        private int transferQuantity(TransferRole role) {
            return switch (role) {
                case LOOSE -> remainingLoose;
                case PACKED_BOX -> 1;
                case OVERFLOW -> remainingPack;
            };
        }

        private ContainerRef overflowContainer() {
            return taskOverflowContainer.orElseGet(
                    () -> configSupplier.get().overflowContainer()
            );
        }
    }

    private enum Phase {
        PREPARE,
        LOOSE,
        PRODUCT_SNAPSHOT,
        STATION,
        AWAIT_PRODUCT,
        TRANSFER_BOX,
        OVERFLOW,
        DONE,
        UNAVAILABLE
    }

    private enum TransferRole {
        LOOSE,
        PACKED_BOX,
        OVERFLOW
    }

    private final class StopState {
        private final List<ContainerAttempt> transfers = new ArrayList<>();
        private final List<PackingWorkcell> workcells = new ArrayList<>();
        private final Map<String, StopOutcome> containerOutcomes = new HashMap<>();
        private StopOutcome navigationOutcome = StopOutcome.CLEAN;
        private StopOutcome containerOutcome = StopOutcome.CLEAN;
        private boolean navigationActive;
        private boolean containersActive;
        private boolean closeAttempted;

        private StopState(TaskState task) {
            for (ItemState item : task.items) {
                if (item.transfer != null) {
                    transfers.add(item.transfer.attempt);
                }
                workcells.add(new PackingWorkcell(
                        item.station(), PackingReleaseMode.AUTO_ON_CLOSE
                ));
            }
        }

        private void refreshContainerOutcome() {
            StopOutcome outcome = StopOutcome.CLEAN;
            for (StopOutcome value : containerOutcomes.values()) {
                outcome = combine(outcome, value);
            }
            containerOutcome = outcome;
            containersActive = outcome == StopOutcome.PENDING;
        }
    }

    private record TransferFlow(
            ContainerAttempt attempt,
            String itemId,
            int quantity,
            int beforeCount
    ) {
    }
}
