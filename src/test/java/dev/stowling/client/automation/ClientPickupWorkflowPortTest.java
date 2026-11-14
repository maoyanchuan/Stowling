package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationOperationStatus;
import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.automation.CustomPickupRules;
import dev.stowling.automation.GroundItem;
import dev.stowling.automation.InMemoryAutomationCheckpointStore;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.PickupAutomationPlan;
import dev.stowling.automation.PickupWorkflowPort.Request;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.RegionItemProbe;
import dev.stowling.schedule.StopOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientPickupWorkflowPortTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String WORKFLOW_ID = "pickup-workflow";
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String OPERATION_KEY = TASK_ID + ":pickup";
    private static final Instant START = Instant.parse("2026-11-15T00:00:00Z");
    private static final BlockPosition STANDBY = new BlockPosition(0, 64, 0);

    @Test
    void waitingProbeIsThrottledToOneCheckPerSecond() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = port(clock, probe, factory);

        AutomationOperationResult started = port.start(
                OPERATION_KEY,
                waitingRequest(Optional.empty())
        );

        assertEquals(AutomationOperationStatus.RUNNING, started.status());
        assertTrue(started.message().orElseThrow().contains("等待物品"));
        assertEquals(List.of(List.of("region-b", "region-a")), probe.observations());

        port.poll(OPERATION_KEY);
        clock.advance(Duration.ofMillis(999));
        port.reconcile(OPERATION_KEY);
        assertEquals(1, probe.observations().size());

        clock.advance(Duration.ofMillis(1));
        port.poll(OPERATION_KEY);
        assertEquals(2, probe.observations().size());
        assertEquals(0, factory.created());
    }

    @Test
    void emptyWaitTransitionsToPresentAndStartsOneEngine() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(true, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = port(clock, probe, factory);

        port.start(OPERATION_KEY, waitingRequest(Optional.empty()));
        probe.setObservation(RegionItemProbe.Observation.PRESENT);
        clock.advance(Duration.ofSeconds(1));

        AutomationOperationResult started = port.poll(OPERATION_KEY);

        assertEquals(AutomationOperationStatus.RUNNING, started.status());
        assertEquals("COLLECT", started.message().orElseThrow());
        assertEquals(1, factory.created());
        assertEquals(WORKFLOW_ID, factory.lastPlan().workflow().id());
        assertEquals(1, factory.navigation().starts());

        port.poll(OPERATION_KEY);
        assertEquals(1, factory.created());
        assertEquals(2, probe.observations().size());
    }

    @Test
    void probeUsesSelectedRegionOrAllRegionsInWorkflowPriorityOrder() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = port(clock, probe, factory);

        port.start(
                OPERATION_KEY + ":selected",
                waitingRequest(Optional.of("region-b"))
        );
        port.start(
                OPERATION_KEY + ":all",
                waitingRequest(Optional.empty())
        );

        assertEquals(
                List.of(
                        List.of("region-b"),
                        List.of("region-b", "region-a")
                ),
                probe.observations()
        );
        assertEquals(0, factory.created());
    }

    @Test
    void unobservableRegionsReturnVisibilityWait() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        probe.setObservation(RegionItemProbe.Observation.UNOBSERVABLE);
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = port(clock, probe, factory);

        AutomationOperationResult result = port.start(
                OPERATION_KEY,
                waitingRequest(Optional.empty())
        );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertEquals("等待区域可见", result.message().orElseThrow());
        assertEquals(0, factory.created());
    }

    @Test
    void waitingDoesNotCreateEngineNavigateOrClick() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = port(clock, probe, factory);

        port.start(OPERATION_KEY, waitingRequest(Optional.empty()));

        assertEquals(0, factory.created());
        assertEquals(0, factory.navigation().starts());
        assertEquals(0, factory.containers().observations());
        assertEquals(0, factory.containers().begins());
    }

    @Test
    void stoppingWaitingEntryIsImmediatelyCleanAndRemembered() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = port(clock, probe, factory);
        port.start(OPERATION_KEY, waitingRequest(Optional.empty()));

        assertEquals(StopOutcome.CLEAN, port.requestStop(TASK_ID));
        assertEquals(
                AutomationOperationStatus.PAUSED,
                port.poll(OPERATION_KEY).status()
        );
        assertEquals(StopOutcome.CLEAN, port.requestStop(TASK_ID));
        assertEquals(0, factory.created());
    }

    @Test
    void legacyRequestStartsImmediatelyWithoutWaitingForItems() {
        RecordingFactory factory = factory(true, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::config,
                factory,
                new TestScheduler()
        );

        AutomationOperationResult result = port.start(
                OPERATION_KEY,
                new Request(WORKFLOW_ID, Optional.empty())
        );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertEquals(1, factory.created());
        assertEquals(1, factory.navigation().starts());
    }

    @Test
    void engineStopUnsafeIsNotReportedCleanOrSwallowed() {
        RecordingFactory factory = factory(true, NavigationStatus.NAVIGATING);
        factory.navigation().setStopOutcome(StopOutcome.UNSAFE);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::config,
                factory,
                new TestScheduler(),
                ignored -> RegionItemProbe.Observation.EMPTY,
                new MutableClock(START)
        );

        port.start(OPERATION_KEY, new Request(WORKFLOW_ID, Optional.empty()));

        assertEquals(StopOutcome.UNSAFE, port.requestStop(TASK_ID));
        assertEquals(StopOutcome.UNSAFE, port.requestStop(TASK_ID));
    }

    @Test
    void customRulesStartImmediatelyWithoutConfiguredWorkflowOrRegion() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(true, NavigationStatus.ARRIVED);
        StowlingConfig loaded = customConfig();
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                () -> loaded, factory, new TestScheduler(), probe, clock
        );

        AutomationOperationResult result = port.start(
                OPERATION_KEY, Request.custom(customRules(), false)
        );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertEquals(1, factory.created());
        assertEquals(1, factory.navigation().starts());
        assertEquals(TASK_ID, factory.lastPlan().taskId());
        assertEquals("custom-pickup", factory.lastPlan().workflow().id());
        assertEquals(CompletionMode.ALL_IDLE, factory.lastPlan().workflow().completionMode());
        assertEquals(Duration.ZERO, factory.lastPlan().workflow().completionIdleDelay());
        assertEquals(CachePolicy.NEAREST_AVAILABLE, factory.lastPlan().workflow().cachePolicy());
        assertEquals(
                Duration.ofSeconds(3),
                factory.lastPlan().orderedRegions().getFirst().idleDelay()
        );
        assertEquals(List.of("custom-cache"), factory.lastPlan().workflow().cacheIds());
        assertEquals(
                ClassificationTiming.AFTER_ROUND,
                factory.lastPlan().workflow().classificationTiming()
        );
        PickupRegion region = factory.lastPlan().regionsById().values().iterator().next();
        assertEquals("custom-pickup-region", region.id());
        assertEquals("custom-standby", region.standbyCoordinateId());
        assertEquals(STANDBY, region.standbyPosition());
        CuboidPickupShape shape = assertInstanceOf(CuboidPickupShape.class, region.shape());
        assertEquals(new BlockPosition(-17, 60, -1), shape.firstCorner());
        assertEquals(new BlockPosition(33, 70, 32), shape.secondCorner());
        assertTrue(probe.observations().isEmpty());
        assertTrue(probe.regionObservations().isEmpty());
        assertTrue(loaded.pickupRegions().isEmpty());
        assertTrue(loaded.pickupWorkflows().isEmpty());
    }

    @Test
    void customWaitingUsesRegionObjectsAndStartsOnceAfterThrottledPresent() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(true, NavigationStatus.ARRIVED);
        AtomicInteger configLoads = new AtomicInteger();
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                () -> {
                    configLoads.incrementAndGet();
                    return customConfig();
                },
                factory,
                new TestScheduler(),
                probe,
                clock
        );

        AutomationOperationResult waiting = port.start(
                OPERATION_KEY, Request.custom(customRules(), true)
        );

        assertEquals(AutomationOperationStatus.RUNNING, waiting.status());
        assertEquals("等待物品出现", waiting.message().orElseThrow());
        assertEquals(1, probe.regionObservations().size());
        assertTrue(probe.observations().isEmpty());
        PickupRegion region = probe.regionObservations().getFirst().getFirst();
        assertInstanceOf(CuboidPickupShape.class, region.shape());
        assertTrue(region.contains(new BlockPosition(-17, 60, -1)));
        assertTrue(region.contains(new BlockPosition(33, 70, 32)));
        assertEquals(0, factory.created());
        assertEquals(0, factory.navigation().starts());
        assertEquals(0, factory.containers().observations());
        assertEquals(0, factory.containers().begins());

        probe.setObservation(RegionItemProbe.Observation.PRESENT);
        port.start(OPERATION_KEY, Request.custom(customRules(), true));
        clock.advance(Duration.ofMillis(999));
        port.reconcile(OPERATION_KEY);
        assertEquals(1, probe.regionObservations().size());
        assertEquals(0, factory.created());

        clock.advance(Duration.ofMillis(1));
        AutomationOperationResult started = port.reconcile(OPERATION_KEY);
        assertEquals(AutomationOperationStatus.RUNNING, started.status());
        assertEquals(1, factory.created());
        assertEquals(1, factory.navigation().starts());
        assertEquals(1, configLoads.get());
        assertEquals(List.of(region), probe.regionObservations().get(1));
        assertEquals(region, factory.lastPlan().regionsById().get(region.id()));

        port.poll(OPERATION_KEY);
        assertEquals(1, factory.created());
        assertEquals(2, probe.regionObservations().size());
    }

    @Test
    void customUnobservableRangeWaitsWithoutStartingAnyActions() {
        RecordingProbe probe = new RecordingProbe();
        probe.setObservation(RegionItemProbe.Observation.UNOBSERVABLE);
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::customConfig,
                factory,
                new TestScheduler(),
                probe,
                new MutableClock(START)
        );

        AutomationOperationResult result = port.start(
                OPERATION_KEY, Request.custom(customRules(), true)
        );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertEquals("等待区域可见", result.message().orElseThrow());
        assertEquals(1, probe.regionObservations().size());
        assertTrue(probe.observations().isEmpty());
        assertEquals(0, factory.created());
        assertEquals(0, factory.navigation().starts());
        assertEquals(0, factory.containers().begins());
    }

    @Test
    void customWaitingStopsCleanlyAndDoesNotRestoreRemovedEntry() {
        MutableClock clock = new MutableClock(START);
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::customConfig,
                factory, new TestScheduler(), probe, clock
        );
        port.start(OPERATION_KEY, Request.custom(customRules(), true));

        assertEquals(StopOutcome.CLEAN, port.requestStop(TASK_ID));
        assertEquals(StopOutcome.CLEAN, port.pollStop(TASK_ID));
        assertEquals(StopOutcome.CLEAN, port.requestStop(TASK_ID));
        probe.setObservation(RegionItemProbe.Observation.PRESENT);
        clock.advance(Duration.ofSeconds(1));
        assertEquals(AutomationOperationStatus.PAUSED, port.poll(OPERATION_KEY).status());
        assertEquals(AutomationOperationStatus.PAUSED, port.reconcile(OPERATION_KEY).status());
        assertEquals(1, probe.regionObservations().size());
        assertEquals(0, factory.created());
    }

    @Test
    void customActiveEngineStopKeepsUnsafeOutcome() {
        RecordingFactory factory = factory(true, NavigationStatus.NAVIGATING);
        factory.navigation().setStopOutcome(StopOutcome.UNSAFE);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::customConfig,
                factory,
                new TestScheduler()
        );
        port.start(OPERATION_KEY, Request.custom(customRules(), false));

        assertEquals(StopOutcome.UNSAFE, port.requestStop(TASK_ID));
        assertEquals(StopOutcome.UNSAFE, port.pollStop(TASK_ID));
        assertEquals(StopOutcome.UNSAFE, port.requestStop(TASK_ID));
    }

    @Test
    void invalidCustomReferenceFailsBeforeObservingOrCreatingEngine() {
        RecordingProbe probe = new RecordingProbe();
        RecordingFactory factory = factory(false, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::customConfig,
                factory, new TestScheduler(), probe, new MutableClock(START)
        );
        CustomPickupRules rules = new CustomPickupRules(
                "custom-standby", "missing-corner", "custom-second", List.of("custom-cache"),
                Duration.ofSeconds(3), ClassificationTiming.AFTER_ROUND
        );

        AutomationOperationResult result = port.start(
                OPERATION_KEY, Request.custom(rules, true)
        );

        assertEquals(AutomationOperationStatus.FAILED, result.status());
        assertTrue(probe.observations().isEmpty());
        assertTrue(probe.regionObservations().isEmpty());
        assertEquals(0, factory.created());
        assertEquals(AutomationOperationStatus.PAUSED, port.reconcile(OPERATION_KEY).status());
    }

    @Test
    void nextCustomPickupStepStartsAfreshAfterPreviousStepStops() {
        RecordingFactory factory = factory(true, NavigationStatus.ARRIVED);
        ClientPickupWorkflowPort port = new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::customConfig, factory, new TestScheduler()
        );
        port.start(OPERATION_KEY, Request.custom(customRules(), false));
        assertEquals(1, factory.navigation().starts());
        assertEquals(StopOutcome.CLEAN, port.requestStop(TASK_ID));

        CustomPickupRules next = new CustomPickupRules(
                "custom-second", "custom-second", "custom-first", List.of("custom-cache"),
                Duration.ofSeconds(7), ClassificationTiming.AFTER_REGION
        );
        AutomationOperationResult result = port.start(
                TASK_ID + ":pickup-next", Request.custom(next, false)
        );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertEquals(2, factory.created());
        assertEquals(2, factory.navigation().starts());
        assertEquals(
                new BlockPosition(33, 70, 32),
                factory.lastPlan().regionsById().get("custom-pickup-region").standbyPosition()
        );
        assertEquals(
                Duration.ofSeconds(7), factory.lastPlan().orderedRegions().getFirst().idleDelay()
        );
        assertEquals(StopOutcome.CLEAN, port.requestStop(TASK_ID));
    }

    @Test
    void stopHelperRequestsBeforePollingAndPropagatesUnsafe() {
        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicBoolean stopPolled = new AtomicBoolean();

        StopOutcome outcome = ClientPickupWorkflowPort.requestStopAndPoll(
                () -> stopRequested.set(true),
                () -> {
                    assertTrue(stopRequested.get());
                    stopPolled.set(true);
                    return StopOutcome.UNSAFE;
                }
        );

        assertEquals(StopOutcome.UNSAFE, outcome);
        assertTrue(stopPolled.get());
    }

    @Test
    void completionSucceedsOnlyAfterCleanStop() {
        AutomationOperationResult result =
                ClientPickupWorkflowPort.stopCompletionResult(
                        "quarry",
                        StopOutcome.CLEAN
                );

        assertEquals(AutomationOperationStatus.SUCCEEDED, result.status());
        assertEquals(
                "RUN_PICKUP:quarry",
                result.lastConfirmedAction().orElseThrow()
        );
    }

    @Test
    void completionRemainsRunningWhileStopIsPending() {
        AutomationOperationResult result =
                ClientPickupWorkflowPort.stopCompletionResult(
                        "quarry",
                        StopOutcome.PENDING
                );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertTrue(result.message().orElseThrow().contains("clean"));
    }

    @Test
    void completionFailsWhenStopIsUnsafe() {
        AutomationOperationResult result =
                ClientPickupWorkflowPort.stopCompletionResult(
                        "quarry",
                        StopOutcome.UNSAFE
                );

        assertEquals(AutomationOperationStatus.FAILED, result.status());
        assertTrue(result.lastConfirmedAction().isEmpty());
    }

    private static ClientPickupWorkflowPort port(
            MutableClock clock,
            RecordingProbe probe,
            RecordingFactory factory
    ) {
        return new ClientPickupWorkflowPort(
                ClientPickupWorkflowPortTest::config,
                factory,
                new TestScheduler(),
                probe,
                clock
        );
    }

    private static Request waitingRequest(Optional<String> regionId) {
        return new Request(WORKFLOW_ID, regionId, true);
    }

    private static RecordingFactory factory(
            boolean buildEngine,
            NavigationStatus navigationStatus
    ) {
        StaticWorld world = new StaticWorld();
        FakeNavigation navigation = new FakeNavigation(navigationStatus);
        NoopContainers containers = new NoopContainers();
        return new RecordingFactory(
                buildEngine,
                world,
                navigation,
                containers
        );
    }

    private static StowlingConfig config() {
        PickupRegion first = new PickupRegion(
                "region-a",
                "Region A",
                DIMENSION,
                STANDBY,
                new SpherePickupShape(STANDBY, 4)
        );
        BlockPosition secondPosition = new BlockPosition(4, 64, 0);
        PickupRegion second = new PickupRegion(
                "region-b",
                "Region B",
                DIMENSION,
                secondPosition,
                new SpherePickupShape(secondPosition, 4)
        );
        NamedCache cache = new NamedCache(
                "cache",
                "Cache",
                DIMENSION,
                new BlockPosition(10, 64, 0),
                ContainerType.CHEST
        );
        PickupWorkflow workflow = new PickupWorkflow(
                WORKFLOW_ID,
                "Pickup Workflow",
                DIMENSION,
                List.of(
                        new PickupRegionBinding("region-a", 10, Duration.ZERO),
                        new PickupRegionBinding("region-b", 20, Duration.ZERO)
                ),
                List.of("cache"),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        ContainerRef overflow = new ContainerRef(
                "overflow",
                DIMENSION,
                new BlockPosition(20, 64, 0),
                ContainerType.CHEST
        );
        return new StowlingConfig(
                5,
                List.of(),
                List.of(first, second),
                List.of(cache),
                List.of(workflow),
                List.of(),
                List.of(),
                List.of(),
                overflow
        );
    }

    private static CustomPickupRules customRules() {
        return new CustomPickupRules(
                "custom-standby", "custom-first", "custom-second", List.of("custom-cache"),
                Duration.ofSeconds(3), ClassificationTiming.AFTER_ROUND
        );
    }

    private static StowlingConfig customConfig() {
        BlockPosition cachePosition = new BlockPosition(10, 64, 0);
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(
                        new NamedCoordinate("custom-standby", "Standby", DIMENSION, STANDBY),
                        new NamedCoordinate(
                                "custom-first", "First", DIMENSION, new BlockPosition(-17, 60, -1)
                        ),
                        new NamedCoordinate(
                                "custom-second", "Second", DIMENSION, new BlockPosition(33, 70, 32)
                        ),
                        new NamedCoordinate("cache-point", "Cache", DIMENSION, cachePosition)
                ),
                List.of(),
                List.of(new NamedCache(
                        "custom-cache", "Cache", "cache-point", DIMENSION,
                        cachePosition, ContainerType.CHEST
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                config().overflowContainer()
        );
    }

    private static PickupAutomationEngine engine(
            PickupAutomationPlan plan,
            StaticWorld world,
            FakeNavigation navigation,
            NoopContainers containers
    ) {
        return new PickupAutomationEngine(
                plan,
                world,
                () -> new PlayerInventorySnapshot(36, List.of()),
                navigation,
                containers,
                request -> {
                    throw new AssertionError("packing must not run");
                },
                () -> START,
                new InMemoryAutomationCheckpointStore()
        );
    }

    private static final class RecordingFactory
            implements ClientPickupWorkflowPort.PickupEngineFactory {
        private final boolean buildEngine;
        private final StaticWorld world;
        private final FakeNavigation navigation;
        private final NoopContainers containers;
        private final List<PickupAutomationPlan> plans = new ArrayList<>();

        private RecordingFactory(
                boolean buildEngine,
                StaticWorld world,
                FakeNavigation navigation,
                NoopContainers containers
        ) {
            this.buildEngine = buildEngine;
            this.world = world;
            this.navigation = navigation;
            this.containers = containers;
        }

        @Override
        public PickupAutomationEngine create(PickupAutomationPlan plan) {
            plans.add(plan);
            if (!buildEngine) {
                throw new AssertionError("waiting must not create an engine");
            }
            return engine(plan, world, navigation, containers);
        }

        private int created() {
            return plans.size();
        }

        private PickupAutomationPlan lastPlan() {
            return plans.getLast();
        }

        private FakeNavigation navigation() {
            return navigation;
        }

        private NoopContainers containers() {
            return containers;
        }
    }

    private static final class RecordingProbe implements RegionItemProbe {
        private final List<List<String>> observations = new ArrayList<>();
        private final List<List<PickupRegion>> regionObservations = new ArrayList<>();
        private Observation observation = Observation.EMPTY;

        @Override
        public Observation observe(List<String> regionIds) {
            observations.add(List.copyOf(regionIds));
            return observation;
        }

        @Override
        public Observation observeRegions(List<PickupRegion> regions) {
            regionObservations.add(List.copyOf(regions));
            return observation;
        }

        private void setObservation(Observation observation) {
            this.observation = observation;
        }

        private List<List<String>> observations() {
            return List.copyOf(observations);
        }

        private List<List<PickupRegion>> regionObservations() {
            return List.copyOf(regionObservations);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    private static final class StaticWorld implements WorldObserver {
        @Override
        public WorldObservation observeWorld() {
            return new WorldObservation(
                    true,
                    true,
                    DIMENSION,
                    Optional.of(STANDBY)
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
        private final NavigationStatus status;
        private int starts;
        private StopOutcome stopOutcome = StopOutcome.CLEAN;

        private FakeNavigation(NavigationStatus status) {
            this.status = status;
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            starts++;
            return status;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return status;
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

        private int starts() {
            return starts;
        }

        private void setStopOutcome(StopOutcome stopOutcome) {
            this.stopOutcome = stopOutcome;
        }
    }

    private static final class NoopContainers implements ContainerInteraction {
        private int observations;
        private int begins;

        @Override
        public Optional<ContainerView> observe(ContainerRef container) {
            observations++;
            return Optional.empty();
        }

        @Override
        public ContainerAttempt begin(
                ContainerTransfer transfer,
                long baselineRevision
        ) {
            begins++;
            throw new AssertionError("container transfer must not begin");
        }

        @Override
        public ContainerAttemptResult poll(ContainerAttempt attempt) {
            throw new AssertionError("container transfer must not poll");
        }

        @Override
        public void cancel(ContainerAttempt attempt) {
        }

        private int observations() {
            return observations;
        }

        private int begins() {
            return begins;
        }
    }

    private static final class TestScheduler implements ClientActionScheduler {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            return CompletableFuture.completedFuture(action.get());
        }
    }
}
