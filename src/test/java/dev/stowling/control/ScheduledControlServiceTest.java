package dev.stowling.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.ConfigLoader;
import dev.stowling.config.StowlingConfig;
import dev.stowling.config.TaskDefinitionNotFoundException;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupSite;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventorySource;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StowlingDatabase;
import dev.stowling.schedule.InMemoryTaskScheduleStore;
import dev.stowling.schedule.RegionItemProbe;
import dev.stowling.schedule.RunOutcome;
import dev.stowling.schedule.ScheduledTaskRuntime;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskRuntimeFactory;
import dev.stowling.schedule.TaskScheduleInfo;
import dev.stowling.schedule.TaskScheduler;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.VersionedTaskDefinition;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduledControlServiceTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void taskDefinitionCrudSavesIndependentlyWithoutEnabling() {
        ServiceFixture fixture = fixture(emptyConfig(), "task-definitions");
        TaskDefinition first = automationTask("first", TaskPriority.MEDIUM);
        TaskDefinition second = automationTask("second", TaskPriority.LOW);
        VersionedTaskDefinition firstCreated = fixture.service()
                .createTaskDefinition(first);
        VersionedTaskDefinition secondCreated = fixture.service()
                .createTaskDefinition(second);
        TaskDefinition firstUpdated = automationTask("first", TaskPriority.HIGH);

        VersionedTaskDefinition updated = fixture.service().updateTaskDefinition(
                first.key(),
                firstCreated.revision(),
                firstUpdated
        );

        assertEquals(TaskPriority.HIGH, updated.definition().settings().priority());
        assertEquals(
                secondCreated.definition(),
                fixture.service().taskDefinition(second.key()).definition()
        );
        assertEquals(
                secondCreated.revision(),
                fixture.service().taskDefinition(second.key()).revision()
        );
        assertTrue(fixture.service().taskDefinitions().stream()
                .anyMatch(definition -> definition.key().equals(first.key())));
        assertFalse(fixture.service().taskScheduling().stream()
                .anyMatch(info -> info.key().equals(first.key()) && info.enabled()));

        fixture.service().enableTaskDefinition(first.key());
        assertTrue(schedule(fixture.service(), first.key()).enabled());
        fixture.service().disableTaskDefinition(first.key());
        assertFalse(schedule(fixture.service(), first.key()).enabled());
        fixture.service().deleteTaskDefinition(first.key(), updated.revision());

        assertThrows(
                TaskDefinitionNotFoundException.class,
                () -> fixture.service().taskDefinition(first.key())
        );
        assertEquals(
                second.key(),
                fixture.service().taskDefinition(second.key()).definition().key()
        );
        StowlingConfig saved = fixture.loader().load(fixture.configPath());
        assertTrue(saved.automationWorkflows().stream()
                .anyMatch(workflow -> workflow.id().equals(second.id())));
        assertFalse(saved.automationWorkflows().stream()
                .anyMatch(workflow -> workflow.id().equals(first.id())));
    }

    @Test
    void runTaskDefinitionEntersSchedulerQueue() {
        ServiceFixture fixture = fixture(emptyConfig(), "run-task");
        TaskDefinition definition = automationTask("queued", TaskPriority.HIGH);
        VersionedTaskDefinition created = fixture.service()
                .createTaskDefinition(definition);

        TaskInfo run = fixture.service().runTaskDefinition(created.key());

        assertEquals(TaskStatus.PENDING.name(), run.status());
        TaskScheduleInfo scheduled = schedule(fixture.service(), created.key());
        assertEquals("QUEUED", scheduled.state());
        assertEquals(run.id(), scheduled.runId());
    }

    @Test
    void legacyRequestsEnterTheSameSchedulerQueue() {
        ServiceFixture fixture = fixture(
                configWithPickupAndAutomation(),
                "legacy-queue"
        );

        TaskInfo pickup = fixture.service().requestPickup(
                new PickupRequest("farm-workflow")
        );
        TaskInfo automation = fixture.service().requestAutomation(
                new AutomationTaskRequest("automation-workflow")
        );

        assertEquals(TaskStatus.PENDING.name(), pickup.status());
        assertEquals(TaskStatus.PENDING.name(), automation.status());
        TaskScheduleInfo pickupRun = schedule(
                fixture.service(),
                new TaskKey(TaskKey.PICKUP, "farm-workflow")
        );
        TaskScheduleInfo automationRun = schedule(
                fixture.service(),
                new TaskKey(TaskKey.AUTOMATION, "automation-workflow")
        );
        assertEquals("QUEUED", pickupRun.state());
        assertEquals("QUEUED", automationRun.state());
        assertEquals(pickup.id(), pickupRun.runId());
        assertEquals(automation.id(), automationRun.runId());
    }

    @Test
    void taskResourcesUpdatePreservesPickupAndAutomationWorkflows() {
        ServiceFixture fixture = fixture(
                configWithPickupAndAutomation(),
                "task-resources"
        );
        PickupConfigSnapshot before = fixture.service().taskResources();
        NamedCache cache = before.cacheContainers().getFirst();
        NamedCache renamed = new NamedCache(
                cache.id(),
                "Renamed cache",
                cache.coordinateId(),
                cache.dimension(),
                cache.position(),
                cache.type()
        );

        PickupConfigSnapshot saved = fixture.service().updateTaskResources(
                new PickupConfigUpdateRequest(
                        before.revision(),
                        before.pickupRegions(),
                        List.of(renamed),
                        List.of()
                )
        );

        assertEquals(before.pickupWorkflows(), saved.pickupWorkflows());
        assertEquals(before.pickupRegions(), saved.pickupRegions());
        assertEquals(
                "Renamed cache",
                saved.cacheContainers().getFirst().name()
        );
        StowlingConfig disk = fixture.loader().load(fixture.configPath());
        assertEquals(before.pickupWorkflows(), disk.pickupWorkflows());
        assertEquals(1, disk.automationWorkflows().size());
        assertEquals("automation-workflow", disk.automationWorkflows().getFirst().id());
    }

    @Test
    void highPriorityPreemptionWaitsForPendingCleanupThenStarts() {
        RuntimeFixture runtime = runtimeFixture(TaskPriority.LOW, TaskPriority.HIGH);
        TaskScheduler scheduler = runtime.scheduler();
        TaskDefinition low = runtime.low();
        TaskDefinition high = runtime.high();

        scheduler.runNow(low);
        scheduler.tick();
        scheduler.runNow(high);
        scheduler.tick();

        assertEquals(List.of("low"), runtime.factory().started());
        assertEquals(1, runtime.factory().runtime("low").stopRequests());
        assertEquals("STOPPING", snapshot(scheduler, low.key()).state());

        scheduler.tick();

        assertEquals(List.of("low"), runtime.factory().started());
        assertEquals(1, runtime.factory().runtime("low").pollRequests());
        assertEquals("STOPPING", snapshot(scheduler, low.key()).state());

        scheduler.tick();

        assertEquals(List.of("low", "high"), runtime.factory().started());
        assertEquals(2, runtime.factory().runtime("low").pollRequests());
        assertEquals("RUNNING", snapshot(scheduler, high.key()).state());
    }

    @Test
    void unsafeCleanupLocksDispatchAndKeepsChallengerQueued() {
        RuntimeFixture runtime = runtimeFixture(
                TaskPriority.LOW,
                TaskPriority.HIGH,
                StopOutcome.UNSAFE
        );
        TaskScheduler scheduler = runtime.scheduler();

        scheduler.runNow(runtime.low());
        scheduler.tick();
        scheduler.runNow(runtime.high());
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertEquals(List.of("low"), runtime.factory().started());
        assertEquals("UNSAFE", snapshot(scheduler, runtime.low().key()).state());
        assertEquals("QUEUED", snapshot(scheduler, runtime.high().key()).state());
    }

    @Test
    void recoveryFailsPendingPickupWithoutReusingCheckpoint() {
        ServiceFixture fixture = fixture(emptyConfig(), "recovery");
        UUID taskId = UUID.randomUUID();
        fixture.tasks().save(new TaskSnapshot(
                taskId,
                TaskKind.PICKUP,
                TaskStatus.PENDING,
                AutomationState.PICKING_UP,
                NOW,
                NOW,
                Optional.empty()
        ));

        fixture.service().recoverPendingPickups();

        TaskSnapshot recovered = fixture.tasks().findById(taskId).orElseThrow();
        assertEquals(TaskStatus.FAILED, recovered.status());
        assertTrue(recovered.failureReason().orElseThrow().contains("checkpoint discarded"));
    }

    private ServiceFixture fixture(StowlingConfig config, String name) {
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve(name + ".json");
        loader.write(configPath, config);
        ConfigFileStore fileStore = new ConfigFileStore(configPath, loader);
        StowlingDatabase database = new StowlingDatabase(
                tempDir.resolve(name + ".db")
        );
        SqliteTaskRepository tasks = new SqliteTaskRepository(database);
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config,
                tasks,
                new SqliteInventoryRepository(database),
                unusedInventorySource(),
                ignored -> {
                    throw new AssertionError("pickup runtime must not start in this test");
                },
                ignored -> {
                },
                () -> loader.load(configPath),
                ignored -> {
                },
                fileStore,
                new UnusedAutomationRuntimeFactory()
        );
        return new ServiceFixture(service, tasks, loader, configPath);
    }

    private static RuntimeFixture runtimeFixture(
            TaskPriority lowPriority,
            TaskPriority highPriority,
            StopOutcome... stopOutcomes
    ) {
        TaskDefinition low = automationTask("low", lowPriority);
        TaskDefinition high = automationTask("high", highPriority);
        RecordingRuntimeFactory factory = new RecordingRuntimeFactory(stopOutcomes);
        TaskScheduler scheduler = new TaskScheduler(
                () -> List.of(low, high),
                factory,
                ignored -> RegionItemProbe.Observation.EMPTY,
                new InMemoryTaskScheduleStore(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new RuntimeFixture(scheduler, factory, low, high);
    }

    private static TaskScheduleInfo schedule(
            StowlingControlServiceImpl service,
            TaskKey key
    ) {
        return service.taskScheduling().stream()
                .filter(info -> info.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static TaskScheduleInfo snapshot(TaskScheduler scheduler, TaskKey key) {
        return scheduler.snapshots().stream()
                .filter(info -> info.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static TaskDefinition automationTask(
            String id,
            TaskPriority priority
    ) {
        AutomationStep wait = new AutomationStep(
                "wait-" + id,
                AutomationStepType.WAIT,
                Map.of("seconds", "1")
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                id,
                id,
                List.of(wait),
                settings(priority)
        );
        return TaskDefinition.from(workflow);
    }

    private static TaskSettings settings(TaskPriority priority) {
        return new TaskSettings(
                priority,
                new TaskTrigger(),
                false,
                TaskInterruption.none()
        );
    }

    private static StowlingConfig emptyConfig() {
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                container("overflow", 40)
        );
    }

    private static StowlingConfig configWithPickupAndAutomation() {
        ContainerRef cache = container("cache", 8);
        PickupSite site = new PickupSite(
                "farm",
                DIMENSION,
                new BlockPosition(0, 64, 0),
                16,
                cache,
                Duration.ZERO
        );
        StowlingConfig pickupBase = new StowlingConfig(
                1,
                Duration.ZERO,
                List.of(site),
                List.of(),
                List.of(),
                List.of(),
                container("overflow", 40)
        );
        AutomationWorkflow automation = new AutomationWorkflow(
                "automation-workflow",
                "Automation workflow",
                List.of(new AutomationStep(
                        "wait",
                        AutomationStepType.WAIT,
                        Map.of("seconds", "1")
                ))
        );
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                pickupBase.coordinatePoints(),
                pickupBase.pickupRegions(),
                pickupBase.cacheContainers(),
                pickupBase.pickupWorkflows(),
                List.of(automation),
                pickupBase.storageRoutes(),
                pickupBase.packingStations(),
                pickupBase.auditSchedules(),
                pickupBase.overflowContainer()
        );
    }

    private static ContainerRef container(String id, int x) {
        return new ContainerRef(
                id,
                DIMENSION,
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    private static InventorySource unusedInventorySource() {
        return new InventorySource() {
            @Override
            public String sourceId() {
                return "unused";
            }

            @Override
            public CompletableFuture<InventoryCapture> capture(ContainerRef container) {
                return new CompletableFuture<>();
            }
        };
    }

    private record ServiceFixture(
            StowlingControlServiceImpl service,
            SqliteTaskRepository tasks,
            ConfigLoader loader,
            Path configPath
    ) {
    }

    private record RuntimeFixture(
            TaskScheduler scheduler,
            RecordingRuntimeFactory factory,
            TaskDefinition low,
            TaskDefinition high
    ) {
    }

    private static final class UnusedAutomationRuntimeFactory
            implements AutomationRuntimeFactory {
        @Override
        public dev.stowling.automation.GenericAutomationEngine create(
                UUID taskId,
                AutomationWorkflow workflow
        ) {
            throw new AssertionError("automation runtime must not start in this test");
        }

        @Override
        public List<dev.stowling.automation.AutomationModuleDescriptor> modules() {
            return List.of();
        }
    }

    private static final class RecordingRuntimeFactory implements TaskRuntimeFactory {
        private final Deque<StopOutcome> stopOutcomes;
        private final List<String> started = new ArrayList<>();
        private final Map<String, RecordingRuntime> runtimes = new java.util.HashMap<>();

        private RecordingRuntimeFactory(StopOutcome... stopOutcomes) {
            this.stopOutcomes = new ArrayDeque<>();
            if (stopOutcomes.length == 0) {
                this.stopOutcomes.addAll(List.of(StopOutcome.PENDING, StopOutcome.CLEAN));
            } else {
                this.stopOutcomes.addAll(List.of(stopOutcomes));
            }
        }

        @Override
        public ScheduledTaskRuntime start(UUID runId, TaskDefinition definition) {
            RecordingRuntime runtime = new RecordingRuntime(stopOutcomes);
            runtimes.put(definition.id(), runtime);
            started.add(definition.id());
            return runtime;
        }

        private List<String> started() {
            return List.copyOf(started);
        }

        private RecordingRuntime runtime(String taskId) {
            return runtimes.get(taskId);
        }
    }

    private static final class RecordingRuntime implements ScheduledTaskRuntime {
        private final Deque<StopOutcome> stopOutcomes;
        private int stopRequests;
        private int pollRequests;

        private RecordingRuntime(Deque<StopOutcome> stopOutcomes) {
            this.stopOutcomes = new ArrayDeque<>(stopOutcomes);
        }

        @Override
        public RunOutcome tick() {
            return new RunOutcome(RunOutcome.RUNNING, "running");
        }

        @Override
        public void requestStop() {
            stopRequests++;
        }

        @Override
        public StopOutcome pollStop() {
            pollRequests++;
            return stopOutcomes.removeFirst();
        }

        private int stopRequests() {
            return stopRequests;
        }

        private int pollRequests() {
            return pollRequests;
        }
    }
}
