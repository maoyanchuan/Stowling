package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupSite;
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class AutomationFakes {
    private AutomationFakes() {
    }

    static final class MutableClock implements AutomationClock {
        private Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public Instant now() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    static final class FakeWorldObserver implements WorldObserver {
        private WorldObservation world;
        private List<GroundItem> groundItems = List.of();
        private final Map<String, List<GroundItem>> regionItems = new HashMap<>();
        private final Map<String, Set<String>> tags = new HashMap<>();
        private PickupRegion lastObservedRegion;
        private boolean filterByRegionGeometry;
        private List<GroundItem> geometryCandidates = List.of();

        FakeWorldObserver(WorldObservation world) {
            this.world = world;
        }

        @Override
        public WorldObservation observeWorld() {
            return world;
        }

        @Override
        public List<GroundItem> observeGroundItems(PickupSite site) {
            return groundItems;
        }

        @Override
        public List<GroundItem> observeGroundItems(PickupRegion region) {
            lastObservedRegion = region;
            List<GroundItem> candidates = filterByRegionGeometry
                    ? geometryCandidates
                    : regionItems.getOrDefault(region.id(), groundItems);
            return candidates.stream()
                    .filter(item -> region.contains(item.position()))
                    .toList();
        }

        @Override
        public Set<String> tagsFor(String itemId) {
            return tags.getOrDefault(itemId, Set.of());
        }

        void setWorld(WorldObservation world) {
            this.world = world;
        }

        void setGroundItems(List<GroundItem> groundItems) {
            this.groundItems = List.copyOf(groundItems);
        }

        void setGroundItems(String regionId, List<GroundItem> items) {
            regionItems.put(regionId, List.copyOf(items));
        }

        void setTags(String itemId, Set<String> itemTags) {
            tags.put(itemId, Set.copyOf(itemTags));
        }

        void setGeometryCandidates(List<GroundItem> items) {
            filterByRegionGeometry = true;
            geometryCandidates = List.copyOf(items);
        }

        PickupRegion lastObservedRegion() {
            return lastObservedRegion;
        }
    }

    static final class FakeInventory implements PlayerInventoryAdapter {
        private PlayerInventorySnapshot snapshot;

        FakeInventory(PlayerInventorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public PlayerInventorySnapshot observe() {
            return snapshot;
        }

        void setSnapshot(PlayerInventorySnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    static final class FakeNavigation implements NavigationPort {
        private BlockPosition position;
        private NavigationStatus startStatus = NavigationStatus.NAVIGATING;
        private NavigationStatus status = NavigationStatus.ARRIVED;
        private int cancelCount;
        private boolean active;
        private boolean failWhenInactive;
        private final List<BlockPosition> startedTargets = new ArrayList<>();
        private StopOutcome stopOutcome = StopOutcome.CLEAN;
        private int stopRequests;

        FakeNavigation(BlockPosition position) {
            this.position = position;
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            startedTargets.add(target);
            active = true;
            if (position.equals(target)) {
                active = false;
                return NavigationStatus.ARRIVED;
            }
            return startStatus;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            if (failWhenInactive && !active) {
                return NavigationStatus.FAILED;
            }
            if (position.equals(target)) {
                active = false;
                return NavigationStatus.ARRIVED;
            }
            if (status == NavigationStatus.ARRIVED) {
                active = false;
            }
            return status;
        }

        @Override
        public void cancel() {
            active = false;
            cancelCount++;
        }

        @Override
        public StopOutcome requestStop() {
            stopRequests++;
            active = false;
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop() {
            return stopOutcome;
        }

        void setPosition(BlockPosition position) {
            this.position = position;
        }

        void setStartStatus(NavigationStatus startStatus) {
            this.startStatus = startStatus;
        }

        void setStatus(NavigationStatus status) {
            this.status = status;
        }

        void setFailWhenInactive(boolean failWhenInactive) {
            this.failWhenInactive = failWhenInactive;
        }

        void setStopOutcome(StopOutcome stopOutcome) {
            this.stopOutcome = stopOutcome;
        }

        List<BlockPosition> startedTargets() {
            return List.copyOf(startedTargets);
        }
    }

    static final class FakeContainers implements ContainerInteraction {
        private final Map<String, ContainerView> views = new HashMap<>();
        private final Deque<ContainerAttemptResult> results = new ArrayDeque<>();
        private final List<ContainerAttempt> attempts = new ArrayList<>();
        private final List<String> observedContainers = new ArrayList<>();
        private final Instant attemptStartedAt;
        private int cancelCount;
        private StopOutcome stopOutcome = StopOutcome.CLEAN;
        private int stopRequests;
        private ContainerAttempt stopAttempt;

        FakeContainers(Instant attemptStartedAt) {
            this.attemptStartedAt = attemptStartedAt;
        }

        @Override
        public Optional<ContainerView> observe(ContainerRef container) {
            observedContainers.add(container.id());
            return Optional.ofNullable(views.get(container.id()));
        }

        @Override
        public ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision) {
            ContainerAttempt attempt = new ContainerAttempt(
                    "attempt-" + attempts.size(),
                    transfer,
                    baselineRevision,
                    attemptStartedAt
            );
            attempts.add(attempt);
            return attempt;
        }

        @Override
        public ContainerAttemptResult poll(ContainerAttempt attempt) {
            if (results.isEmpty()) {
                return new ContainerAttemptResult(
                        ContainerAttemptStatus.PENDING,
                        attempt.baselineRevision(),
                        Optional.empty(),
                        Optional.empty(),
                        "awaiting server acknowledgement"
                );
            }
            return results.removeFirst();
        }

        @Override
        public void cancel(ContainerAttempt attempt) {
            cancelCount++;
        }

        @Override
        public StopOutcome requestStop(ContainerAttempt attempt) {
            stopRequests++;
            stopAttempt = attempt;
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop(ContainerAttempt attempt) {
            return stopOutcome;
        }

        void putView(ContainerView view) {
            views.put(view.containerId(), view);
        }

        void addResult(ContainerAttemptResult result) {
            results.addLast(result);
        }

        List<ContainerAttempt> attempts() {
            return List.copyOf(attempts);
        }

        List<String> observedContainers() {
            return List.copyOf(observedContainers);
        }

        void setStopOutcome(StopOutcome stopOutcome) {
            this.stopOutcome = stopOutcome;
        }

        int stopRequests() {
            return stopRequests;
        }
    }

    static final class FakePacking implements PackingPort {
        private PackingDisposition disposition = PackingDisposition.ACCEPTED;
        private final List<PackingRequest> requests = new ArrayList<>();
        private int submits;
        private int polls;
        private int stopRequests;
        private StopOutcome stopOutcome = StopOutcome.CLEAN;

        @Override
        public PackingDisposition submit(PackingRequest request) {
            submits++;
            requests.add(request);
            return disposition;
        }

        @Override
        public PackingDisposition poll(PackingRequest request) {
            polls++;
            return disposition;
        }

        @Override
        public StopOutcome requestStop(UUID taskId) {
            stopRequests++;
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop(UUID taskId) {
            return stopOutcome;
        }

        void setDisposition(PackingDisposition disposition) {
            this.disposition = disposition;
        }

        List<PackingRequest> requests() {
            return List.copyOf(requests);
        }

        void setStopOutcome(StopOutcome stopOutcome) {
            this.stopOutcome = stopOutcome;
        }

        int submits() {
            return submits;
        }

        int polls() {
            return polls;
        }

        int stopRequests() {
            return stopRequests;
        }
    }

    static ContainerView view(
            ContainerRef container,
            long revision,
            List<ContainerSlot> slots
    ) {
        return new ContainerView(
                container.id(),
                container.dimension(),
                revision,
                container.type() == dev.stowling.domain.ContainerType.SHULKER_BOX ? 27 : 54,
                slots
        );
    }

    static ContainerAttemptResult acknowledged(
            ContainerRef destination,
            long revision,
            List<ContainerSlot> slots
    ) {
        return new ContainerAttemptResult(
                ContainerAttemptStatus.ACKNOWLEDGED,
                revision,
                Optional.empty(),
                Optional.of(view(destination, revision, slots)),
                "server revision and slots confirmed"
        );
    }

    static PlayerInventorySnapshot emptyInventory() {
        return new PlayerInventorySnapshot(36, List.of());
    }

    static PlayerInventorySnapshot inventory(String itemId, int count) {
        return new PlayerInventorySnapshot(
                36,
                List.of(new PlayerInventorySlot(0, itemId, count))
        );
    }

    static Set<String> keys(String value) {
        Set<String> result = new HashSet<>();
        result.add(value);
        return Set.copyOf(result);
    }
}
