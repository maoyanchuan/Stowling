package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import dev.stowling.packing.PackingBoxContents;
import dev.stowling.packing.PackingStationTickPort;
import dev.stowling.packing.PackingWorkcell;
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TickPackingPortTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String DIAMOND = "minecraft:diamond";
    private static final String SHULKER = "minecraft:shulker_box";
    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant START = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void belowThresholdMovesLooseItemsBeforeAccepting() {
        Fixture fixture = new Fixture(10, 8, false);

        assertEquals(PackingDisposition.PENDING, fixture.port.submit(fixture.request()));
        assertEquals(PackingDisposition.PENDING, fixture.port.submit(fixture.request()));
        assertEquals(PackingDisposition.PENDING, fixture.port.submit(fixture.request()));
        assertEquals(PackingDisposition.PENDING, fixture.port.submit(fixture.request()));
        assertEquals(PackingDisposition.ACCEPTED, fixture.port.submit(fixture.request()));
        assertEquals(1, fixture.containers.transfers.size());
    }

    @Test
    void fullBoxIsTransferredBeforeAcceptance() {
        Fixture fixture = new Fixture(1, 1, false);

        PackingDisposition disposition = PackingDisposition.PENDING;
        for (int tick = 0; tick < 20 && disposition == PackingDisposition.PENDING; tick++) {
            if (tick == 5) {
                fixture.containers.put(fixture.product, SHULKER, 1);
            }
            disposition = fixture.port.submit(fixture.request());
        }

        assertEquals(PackingDisposition.ACCEPTED, disposition);
        assertTrue(fixture.station.sources.stream().anyMatch(source ->
                source.id().equals(fixture.buffer.id())));
        assertEquals(1, fixture.containers.transfers.stream()
                .filter(transfer -> transfer.items().getFirst().itemId().equals(SHULKER))
                .count());
    }

    @Test
    void timedOutStationFailsOverInConfiguredOrder() {
        Fixture fixture = new Fixture(1, 1, true);
        fixture.station.fail.add("station-a");
        fixture.station.fail.add("station-b");

        for (int tick = 0; tick < 4; tick++) {
            fixture.port.submit(fixture.request());
        }
        fixture.clock.advance(Duration.ofSeconds(31));
        for (int tick = 0; tick < 4; tick++) {
            fixture.port.submit(fixture.request());
        }

        assertTrue(fixture.station.prepared.contains("station-a"));
        assertTrue(fixture.station.prepared.contains("station-b"));
    }

    @Test
    void stopPollReplacesPendingNavigationAndRemembersCleanOutcome() {
        Fixture fixture = new Fixture(10, 0, false);
        assertEquals(PackingDisposition.PENDING, fixture.port.submit(fixture.request()));
        fixture.navigation.stopOutcome = StopOutcome.PENDING;

        assertEquals(StopOutcome.PENDING, fixture.port.requestStop(TASK));

        fixture.navigation.stopOutcome = StopOutcome.CLEAN;
        assertEquals(StopOutcome.CLEAN, fixture.port.pollStop(TASK));
        assertEquals(StopOutcome.CLEAN, fixture.port.pollStop(TASK));
    }

    @Test
    void acceptedPackingRequestRemainsIdempotentlyCleanStoppable() {
        Fixture fixture = new Fixture(10, 8, false);
        PackingDisposition disposition = PackingDisposition.PENDING;
        for (int tick = 0; tick < 10
                && disposition == PackingDisposition.PENDING; tick++) {
            disposition = fixture.port.submit(fixture.request());
        }
        assertEquals(PackingDisposition.ACCEPTED, disposition);

        assertEquals(StopOutcome.CLEAN, fixture.port.requestStop(TASK));
        assertEquals(StopOutcome.CLEAN, fixture.port.pollStop(TASK));
    }

    @Test
    void explicitCompletionWaitsForSafeExitWithoutRepeatingTransfers() {
        Fixture fixture = new Fixture(10, 8, false, true);
        fixture.navigation.stopOutcome = StopOutcome.PENDING;
        for (int tick = 0; tick < 20; tick++) {
            assertEquals(PackingDisposition.PENDING,
                    fixture.port.submit(fixture.explicitRequest()));
        }
        int transferCount = fixture.containers.transfers.size();
        assertTrue(transferCount > 0);
        assertEquals(StopOutcome.PENDING, fixture.port.pollStop(TASK));
        assertEquals(PackingDisposition.PENDING,
                fixture.port.submit(fixture.explicitRequest()));
        assertEquals(transferCount, fixture.containers.transfers.size());

        fixture.navigation.stopOutcome = StopOutcome.CLEAN;
        assertEquals(PackingDisposition.ACCEPTED,
                fixture.port.submit(fixture.explicitRequest()));
        assertEquals(transferCount, fixture.containers.transfers.size());
        assertEquals(StopOutcome.CLEAN, fixture.port.pollStop(TASK));
    }

    @Test
    void explicitCompletionDoesNotSucceedWhenSafeExitIsUnconfirmed() {
        Fixture fixture = new Fixture(10, 8, false, true);
        fixture.navigation.stopOutcome = StopOutcome.UNSAFE;
        PackingDisposition disposition = PackingDisposition.PENDING;
        for (int tick = 0; tick < 20 && disposition == PackingDisposition.PENDING; tick++) {
            disposition = fixture.port.submit(fixture.explicitRequest());
        }

        assertEquals(PackingDisposition.UNAVAILABLE, disposition);
        assertEquals(StopOutcome.UNSAFE, fixture.port.pollStop(TASK));
    }

    @Test
    void explicitTaskPlanBypassesSharedRoutesAndStations() {
        Fixture fixture = new Fixture(1, 1, true, true);

        assertEquals(
                PackingDisposition.PENDING,
                fixture.port.submit(fixture.explicitRequest())
        );
    }

    @Test
    void explicitTaskPlanUsesItsOwnOverflowContainer() {
        Fixture fixture = new Fixture(1, 1, true, true);
        fixture.containers.put(fixture.overflow, DIAMOND, 0);
        fixture.station.fail.add("station-a");
        fixture.station.fail.add("station-b");

        for (int tick = 0; tick < 20; tick++) {
            fixture.port.submit(fixture.explicitRequest());
            if (tick % 2 == 0) {
                fixture.clock.advance(Duration.ofSeconds(31));
            }
        }

        assertTrue(fixture.containers.transfers.stream().anyMatch(transfer ->
                transfer.destination().equals(fixture.overflow)));
    }

    private static final class Fixture {
        private final MutableClock clock = new MutableClock(START);
        private final FakeContainerInteraction containers = new FakeContainerInteraction();
        private final FakeStation station = new FakeStation();
        private final FakeNavigation navigation = new FakeNavigation();
        private final ContainerRef buffer = container("buffer", 0, ContainerType.CHEST);
        private final ContainerRef bulk = container("bulk", 10, ContainerType.CHEST);
        private final ContainerRef overflow = container("overflow", 30, ContainerType.CHEST);
        private final ContainerRef product = container("product", 20, ContainerType.CHEST);
        private final List<PackingStation> stations;
        private final StorageRoute route;
        private final TaskPackingPlan taskPlan;
        private final TickPackingPort port;

        private Fixture(int routeThreshold, int bulkCount, boolean twoStations) {
            this(routeThreshold, bulkCount, twoStations, false);
        }

        private Fixture(
                int routeThreshold,
                int bulkCount,
                boolean twoStations,
                boolean explicitPlan
        ) {
            containers.put(bulk, DIAMOND, bulkCount);
            containers.put(product, SHULKER, 0);
            stations = twoStations
                    ? List.of(station("station-a"), station("station-b"))
                    : List.of(station("station-a"));
            route = new StorageRoute(
                    "diamond",
                    List.of(DIAMOND),
                    List.of(),
                    10,
                    bulk,
                    PackingTrigger.ON_ITEM_COUNT,
                    routeThreshold,
                    stations.stream().map(PackingStation::id).toList()
            );
            PickupSite pickupSite = new PickupSite(
                    "site",
                    DIMENSION,
                    new BlockPosition(0, 64, 0),
                    16,
                    buffer,
                    Duration.ofSeconds(60)
            );
            StowlingConfig config = new StowlingConfig(
                    1,
                    Duration.ofSeconds(60),
                    List.of(pickupSite),
                    List.of(route),
                    stations,
                    List.of(),
                    overflow
            );
            taskPlan = new TaskPackingPlan(route, stations, overflow);
            Supplier<StowlingConfig> configSupplier = explicitPlan
                    ? () -> {
                        throw new AssertionError(
                                "explicit task plan must not read shared config"
                        );
                    }
                    : () -> config;
            port = new TickPackingPort(
                    configSupplier,
                    new FakeWorld(),
                    navigation,
                    containers,
                    station,
                    clock
            );
        }

        private dev.stowling.automation.PackingRequest request() {
            return new dev.stowling.automation.PackingRequest(
                    TASK,
                    buffer,
                    route.id(),
                    stations.stream().map(PackingStation::id).toList(),
                    List.of(new ItemAmount(DIAMOND, 2))
            );
        }

        private dev.stowling.automation.PackingRequest explicitRequest() {
            return new dev.stowling.automation.PackingRequest(
                    TASK,
                    buffer,
                    route.id(),
                    stations.stream().map(PackingStation::id).toList(),
                    List.of(new ItemAmount(DIAMOND, 2)),
                    Optional.of(taskPlan)
            );
        }

        private PackingStation station(String id) {
            return new PackingStation(
                    id,
                    DIMENSION,
                    new BlockPosition(40, 64, 0),
                    product,
                    PackingTrigger.ON_ITEM_COUNT,
                    1
            );
        }
    }

    private static final class FakeWorld implements WorldObserver {
        @Override
        public WorldObservation observeWorld() {
            return new WorldObservation(
                    true,
                    true,
                    DIMENSION,
                    Optional.of(new BlockPosition(0, 64, 0))
            );
        }

        @Override
        public List<GroundItem> observeGroundItems(PickupSite site) {
            return List.of();
        }

        @Override
        public Set<String> tagsFor(String itemId) {
            return Set.of();
        }
    }

    private static final class FakeNavigation implements NavigationPort {
        private StopOutcome stopOutcome = StopOutcome.CLEAN;

        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop() {
            return stopOutcome;
        }
    }

    private static final class FakeContainerInteraction implements ContainerInteraction {
        private final Map<String, ContainerView> views = new HashMap<>();
        private final Map<String, Transfer> transfersById = new HashMap<>();
        private final List<ContainerTransfer> transfers = new ArrayList<>();
        private int nextId;

        @Override
        public Optional<ContainerView> observe(ContainerRef container) {
            return Optional.ofNullable(views.get(container.id()));
        }

        @Override
        public ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision) {
            String id = "attempt-" + nextId++;
            transfers.add(transfer);
            transfersById.put(id, new Transfer(transfer, baselineRevision));
            return new ContainerAttempt(id, transfer, baselineRevision, START);
        }

        @Override
        public ContainerAttemptResult poll(ContainerAttempt attempt) {
            Transfer transfer = transfersById.get(attempt.attemptId());
            if (transfer.pending) {
                transfer.pending = false;
                return new ContainerAttemptResult(
                        ContainerAttemptStatus.PENDING,
                        transfer.baseline,
                        Optional.empty(),
                        Optional.empty(),
                        "awaiting confirmation"
                );
            }
            int quantity = transfer.transfer.items().getFirst().count();
            String itemId = transfer.transfer.items().getFirst().itemId();
            ContainerView current = views.get(transfer.transfer.destination().id());
            int count = current.itemCounts().getOrDefault(itemId, 0) + quantity;
            ContainerView after = view(
                    transfer.transfer.destination(),
                    current.revision() + 1,
                    itemId,
                    count
            );
            views.put(after.containerId(), after);
            return new ContainerAttemptResult(
                    ContainerAttemptStatus.ACKNOWLEDGED,
                    after.revision(),
                    Optional.empty(),
                    Optional.of(after),
                    "confirmed"
            );
        }

        @Override
        public void cancel(ContainerAttempt attempt) {
            transfersById.remove(attempt.attemptId());
        }

        @Override
        public StopOutcome requestStop(ContainerAttempt attempt) {
            return StopOutcome.CLEAN;
        }

        @Override
        public StopOutcome pollStop(ContainerAttempt attempt) {
            return StopOutcome.CLEAN;
        }

        private void put(ContainerRef container, String itemId, int count) {
            views.put(container.id(), view(container, nextId++, itemId, count));
        }
    }

    private static final class FakeStation implements PackingStationTickPort {
        private final Set<String> fail = new HashSet<>();
        private final List<String> prepared = new ArrayList<>();
        private final List<ContainerRef> sources = new ArrayList<>();
        private int boxCount;
        private int pendingInsert;
        private int confirmedInsert;
        private boolean awaiting;

        @Override
        public boolean prepare(PackingWorkcell workcell) {
            prepared.add(workcell.station().id());
            if (fail.contains(workcell.station().id())) {
                return false;
            }
            if (pendingInsert > 0) {
                boxCount += pendingInsert;
                confirmedInsert += pendingInsert;
                pendingInsert = 0;
            }
            return true;
        }

        @Override
        public int takeConfirmedInsertion(PackingWorkcell workcell) {
            int result = confirmedInsert;
            confirmedInsert = 0;
            return result;
        }

        @Override
        public void setInsertionSource(
                PackingWorkcell workcell,
                Optional<ContainerRef> source
        ) {
            source.ifPresent(sources::add);
        }

        @Override
        public boolean awaitingPackedBox(PackingWorkcell workcell) {
            return awaiting;
        }

        @Override
        public void clearAwaitingPackedBox(PackingWorkcell workcell) {
            awaiting = false;
        }

        @Override
        public PackingBoxContents inspect(PackingWorkcell workcell) {
            Map<String, Integer> counts = boxCount == 0
                    ? Map.of()
                    : Map.of(DIAMOND, boxCount);
            return new PackingBoxContents(counts, boxCount == 0 ? 0 : 1, 27);
        }

        @Override
        public int insert(PackingWorkcell workcell, String itemId, int maximumQuantity) {
            if (pendingInsert == 0 && boxCount == 0) {
                pendingInsert = maximumQuantity;
            }
            return 0;
        }

        @Override
        public void close(PackingWorkcell workcell) {
        }

        @Override
        public void triggerBreak(PackingWorkcell workcell) {
        }

        @Override
        public boolean awaitPackedBoxInProductContainer(
                PackingWorkcell workcell,
                String itemId
        ) {
            awaiting = true;
            return false;
        }
    }

    private static final class Transfer {
        private final ContainerTransfer transfer;
        private final long baseline;
        private boolean pending = true;

        private Transfer(ContainerTransfer transfer, long baseline) {
            this.transfer = transfer;
            this.baseline = baseline;
        }
    }

    private static final class MutableClock implements AutomationClock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static ContainerRef container(String id, int x, ContainerType type) {
        return new ContainerRef(id, DIMENSION, new BlockPosition(x, 64, 0), type);
    }

    private static ContainerView view(
            ContainerRef container,
            long revision,
            String itemId,
            int count
    ) {
        List<ContainerSlot> slots = count == 0
                ? List.of()
                : List.of(new ContainerSlot(0, itemId, count));
        return new ContainerView(container.id(), DIMENSION, revision, 54, slots);
    }
}
