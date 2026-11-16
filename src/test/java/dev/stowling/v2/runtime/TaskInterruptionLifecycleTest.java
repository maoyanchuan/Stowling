package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerCategoryPort;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.modules.StandardTaskModuleFactories;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeFactory;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskInterruptionLifecycleTest {
    private static final String ADD_MANAGED = "TEST_ADD_MANAGED";
    private static final String CLEAR_MANAGED = "TEST_CLEAR_MANAGED";
    private static final String SLOW = "TEST_SLOW";
    private static final String NOOP = "TEST_NOOP";
    private static final String FLAKY_CLEANUP = "TEST_FLAKY_CLEANUP";

    @Test
    void createTaskAlwaysQueuesIndependentRunsAndKeepsTargetPriority() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition child = task(
                "Child",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                moveStep(coordinateId)
        );
        TaskDefinition parent = task(
                "Parent",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of("taskRef", "task:" + child.id())
                ),
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of("taskRef", "task:" + child.id())
                ),
                actionStep(SLOW)
        );
        AppConfig config = config(
                List.of(coordinate(coordinateId)),
                List.of(parent, child)
        );
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );

        TaskRunSnapshot parentRun = manager.start(parent, config, null);
        manager.tick();

        assertEquals(2, manager.queued().size());
        assertTrue(manager.queued().stream().allMatch(
                run -> run.priority() == TaskPriority.BACKGROUND
        ));
        TaskRunSnapshot childRun = manager.queued().getFirst();
        assertEquals(child.id(), childRun.taskId());

        manager.cancel(parentRun.runId());
        manager.tick();

        assertEquals(
                child.id(),
                manager.status().orElseThrow().taskId()
        );
    }


    @Test
    void createdHighPriorityTaskRequestsInterruptionWithoutNesting() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition child = task(
                "Created urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(coordinateId)
        );
        TaskDefinition parent = task(
                "Parent",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of("taskRef", "task:" + child.id())
                ),
                actionStep(SLOW)
        );
        AppConfig config = config(
                List.of(coordinate(coordinateId)),
                List.of(parent, child)
        );
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );

        TaskRunSnapshot parentRun = manager.start(parent, config, null);
        manager.tick();

        assertEquals(
                TaskRunState.INTERRUPTING,
                runState(manager, parentRun.runId())
        );
        assertEquals(
                TaskPriority.URGENT,
                manager.queued().getFirst().priority()
        );
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }
        assertTrue(manager.runs().stream().anyMatch(
                run -> run.taskId().equals(child.id())
                        && run.state() == TaskRunState.SUCCEEDED
        ));
    }

    @Test
    void preemptionRunsCustomCleanupBeforeUrgentTask() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(CLEAR_MANAGED),
                moveStep(cleanupPoint)
        );
        TaskDefinition background = task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );
        background = withCleanup(background, cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(
                        coordinate(cleanupPoint, 10),
                        coordinate(urgentPoint, 20)
                ),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot backgroundRun = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 8; tick++) {
            manager.tick();
        }

        assertEquals(
                List.of(
                        new BlockPosition(10, 64, 10),
                        new BlockPosition(20, 64, 10)
                ),
                navigation.positions()
        );
        assertEquals(
                TaskRunState.PREEMPTED,
                runState(manager, backgroundRun.runId())
        );
        assertTrue(manager.runs().stream().anyMatch(
                run -> run.taskName().equals("Urgent")
                        && run.state() == TaskRunState.SUCCEEDED
        ));
    }

    @Test
    void defaultCleanupRunsAfterCancellation() {
        UUID cleanupPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = new TaskDefinition(
                UUID.randomUUID(),
                "Default Cleanup",
                TaskPriority.BACKGROUND,
                false,
                List.of(actionStep(CLEAR_MANAGED), moveStep(cleanupPoint)),
                TaskInterruptionMode.NONE,
                null,
                true,
                false
        );
        TaskDefinition running = task(
                "Running",
                TaskPriority.NORMAL,
                TaskInterruptionMode.DEFAULT,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );
        AppConfig config = config(
                List.of(coordinate(cleanupPoint)),
                List.of(running, cleanup)
        );

        TaskRunSnapshot run = manager.start(running, config, null);
        manager.tick();
        manager.cancel(run.runId());
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(TaskRunState.CANCELLED, runState(manager, run.runId()));
        assertEquals(
                List.of(new BlockPosition(10, 64, 10)),
                navigation.positions()
        );
    }

    @Test
    void cleanupFailureKeepsTheSlotAndDoesNotStartQueuedTask() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.failAt(new BlockPosition(10, 64, 10));
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(NOOP),
                moveStep(cleanupPoint)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(coordinate(cleanupPoint), coordinate(urgentPoint)),
                List.of(background, cleanup, urgent)
        );

        manager.start(background, config, null);
        manager.tick();
        TaskRunSnapshot urgentRun = manager.start(urgent, config, null);
        for (int tick = 0; tick < 8; tick++) {
            manager.tick();
        }

        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );
        assertTrue(manager.busy());
        assertEquals(
                urgentRun.runId(),
                manager.queued().getFirst().runId()
        );
    }


    @Test
    void cleanupFailureRequestsStopForNewCleanupNavigation() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.stopOutcomes(
                StopOutcome.PENDING,
                StopOutcome.CLEAN,
                StopOutcome.CLEAN
        );
        navigation.failAt(new BlockPosition(10, 64, 10));
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                moveStep(cleanupPoint)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(
                        coordinate(cleanupPoint, 10),
                        coordinate(urgentPoint, 20)
                ),
                List.of(background, cleanup, urgent)
        );

        manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );
        assertEquals(2, navigation.requestStopCount());
        assertEquals(1, navigation.pollStopCount());
    }

    @Test
    void retryReconfirmsNavigationStopBeforeCleanup() {
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.stopOutcomes(StopOutcome.UNSAFE, StopOutcome.CLEAN);
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(CLEAR_MANAGED)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(coordinate(urgentPoint)),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot run = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        manager.tick();
        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );
        assertThrows(
                IllegalStateException.class,
                () -> manager.skipInterruption(run.runId())
        );

        manager.retryInterruption(run.runId());
        manager.tick();
        manager.tick();
        manager.tick();

        assertEquals(
                TaskRunState.PREEMPTED,
                runState(manager, run.runId())
        );
        assertTrue(manager.runs().stream().anyMatch(
                candidate -> candidate.taskId().equals(urgent.id())
        ));
    }



    @Test
    void retryDrainsInFlightCleanupBeforeStartingItAgain() {
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        AtomicInteger attempts = new AtomicInteger();
        TaskModuleRuntimeFactory flaky = new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return FLAKY_CLEANUP;
            }

            @Override
            public TaskModuleExecutor create(
                    TaskStep step,
                    TaskExecutionContext context
            ) {
                int attempt = attempts.incrementAndGet();
                return new TestAction(context) {
                    private boolean stopping;
                    private boolean terminal;

                    @Override
                    public TaskModuleResult start() {
                        if (attempt == 1) {
                            return TaskModuleResult.waiting("opening");
                        }
                        context.workspace().consumeManaged(
                                "minecraft:iron_ingot",
                                1
                        );
                        terminal = true;
                        return TaskModuleResult.succeeded("cleaned");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        if (attempt > 1) {
                            terminal = true;
                            return TaskModuleResult.succeeded("cleaned");
                        }
                        if (stopping) {
                            terminal = true;
                            return TaskModuleResult.failed("drained failure");
                        }
                        throw new IllegalStateException("interruption error");
                    }

                    @Override
                    public void requestStop() {
                        stopping = true;
                    }

                    @Override
                    public boolean safeToStop() {
                        return terminal;
                    }
                };
            }
        };
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation, flaky)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(FLAKY_CLEANUP)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(coordinate(urgentPoint)),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot run = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        manager.tick();
        manager.tick();
        manager.tick();
        manager.tick();
        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );

        manager.retryInterruption(run.runId());
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(TaskRunState.PREEMPTED, runState(manager, run.runId()));
        assertEquals(2, attempts.get());
    }

    @Test
    void cancellationWithNoCleanupFinishesAndReportsManagedResidue() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition task = task(
                "No cleanup",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );

        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();
        manager.cancel(run.runId());
        manager.tick();

        TaskRunSnapshot snapshot = manager.runs().stream()
                .filter(candidate -> candidate.runId().equals(run.runId()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskRunState.CANCELLED, snapshot.state());
        assertTrue(snapshot.message().contains("受管物品"));
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void missingDefaultCleanupDoesNotCreatePermanentFailure() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition task = task(
                "Default absent",
                TaskPriority.NORMAL,
                TaskInterruptionMode.DEFAULT,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );

        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();
        manager.cancel(run.runId());
        manager.tick();

        TaskRunSnapshot snapshot = manager.runs().stream()
                .filter(candidate -> candidate.runId().equals(run.runId()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskRunState.CANCELLED, snapshot.state());
        assertTrue(snapshot.message().contains("未执行清理"));
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void ordinaryTaskSuccessDoesNotRequireEmptyManagedLedger() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition task = task(
                "Single action",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED)
        );

        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();

        assertEquals(TaskRunState.SUCCEEDED, runState(manager, run.runId()));
        assertTrue(manager.status().isEmpty());
    }

    private static TaskRunManager managerWithRegistry(
            RecordingNavigation navigation
    ) {
        return new TaskRunManager(navigation, registry(navigation));
    }

    private static TaskModuleRuntimeRegistry registry(
            RecordingNavigation navigation,
            TaskModuleRuntimeFactory... extraFactories
    ) {
        List<TaskModuleRuntimeFactory> factories = new ArrayList<>(
                StandardTaskModuleFactories.create()
        );
        factories.add(factory(ADD_MANAGED));
        factories.add(factory(CLEAR_MANAGED));
        factories.add(factory(SLOW));
        factories.add(factory(NOOP));
        factories.addAll(List.of(extraFactories));
        return new TaskModuleRuntimeRegistry(
                navigation,
                new EmptyDroppedItems(),
                (region, types, volume) ->
                        ContainerScanResult.failure("unused"),
                containers -> ContainerCategoryResult.failure("unused"),
                new FailedTransfers(),
                new FailedWithdrawals(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                FoodConsumptionPort.UNAVAILABLE,
                (zone, types, volume) -> CategoryZoneResult.failure("unused"),
                factories
        );
    }

    private static TaskModuleRuntimeFactory factory(String moduleId) {
        return new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return moduleId;
            }

            @Override
            public TaskModuleExecutor create(
                    TaskStep step,
                    TaskExecutionContext context
            ) {
                return switch (moduleId) {
                    case ADD_MANAGED -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            context.workspace().recordManagedAcquired(
                                    "minecraft:iron_ingot",
                                    1
                            );
                            return TaskModuleResult.succeeded("added");
                        }
                    };
                    case CLEAR_MANAGED -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            context.workspace().consumeManaged(
                                    "minecraft:iron_ingot",
                                    1
                            );
                            return TaskModuleResult.succeeded("cleared");
                        }
                    };
                    case SLOW -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            return TaskModuleResult.waiting("slow");
                        }
                    };
                    default -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            return TaskModuleResult.succeeded("noop");
                        }
                    };
                };
            }
        };
    }

    private abstract static class TestAction implements TaskModuleExecutor {
        private final TaskExecutionContext context;

        protected TestAction(TaskExecutionContext context) {
            this.context = context;
        }

        @Override
        public TaskModuleResult tick() {
            return TaskModuleResult.waiting("slow");
        }

        @Override
        public void cancel() {
        }
    }

    private static TaskDefinition withCleanup(
            TaskDefinition task,
            TaskDefinition cleanup,
            boolean requeue
    ) {
        return new TaskDefinition(
                task.id(),
                task.name(),
                task.priority(),
                task.repeatWhenIdle(),
                task.steps(),
                TaskInterruptionMode.CUSTOM,
                "task:" + cleanup.id(),
                false,
                requeue
        );
    }

    private static TaskDefinition task(
            String name,
            TaskPriority priority,
            TaskInterruptionMode mode,
            TaskStep... steps
    ) {
        return new TaskDefinition(
                UUID.randomUUID(),
                name,
                priority,
                false,
                List.of(steps),
                mode,
                null,
                false,
                false
        );
    }

    private static TaskStep actionStep(String moduleId) {
        return TaskStep.action(UUID.randomUUID(), moduleId, Map.of());
    }

    private static TaskStep moveStep(UUID coordinateId) {
        return TaskStep.action(
                UUID.randomUUID(),
                "MOVE_TO_COORDINATE",
                Map.of("targetRef", "coordinate:" + coordinateId)
        );
    }

    private static CoordinatePoint coordinate(UUID id) {
        return coordinate(id, 10);
    }

    private static CoordinatePoint coordinate(UUID id, int x) {
        return new CoordinatePoint(id, "Target", x, 64, 10);
    }

    private static AppConfig config(
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks
    ) {
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                tasks,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static TaskRunState runState(TaskRunManager manager, UUID runId) {
        return manager.runs().stream()
                .filter(run -> run.runId().equals(runId))
                .findFirst()
                .orElseThrow()
                .state();
    }

    private static final class RecordingNavigation implements NavigationPort {
        private final Deque<NavigationStatus> statuses = new ArrayDeque<>();
        private final Deque<StopOutcome> stopOutcomes = new ArrayDeque<>();
        private final List<BlockPosition> positions = new ArrayList<>();
        private BlockPosition failAt;
        private boolean active;
        private int requestStopCount;
        private int pollStopCount;

        @Override
        public NavigationStatus start(BlockPosition target) {
            positions.add(target);
            active = true;
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return status(target, null);
        }

        @Override
        public NavigationStatus status(BlockPosition target, Integer radius) {
            if (!active) {
                return NavigationStatus.FAILED;
            }
            if (target.equals(failAt)) {
                active = false;
                return NavigationStatus.FAILED;
            }
            NavigationStatus status = statuses.isEmpty()
                    ? NavigationStatus.ARRIVED
                    : statuses.removeFirst();
            if (status != NavigationStatus.NAVIGATING) {
                active = false;
            }
            return status;
        }

        @Override
        public void cancel() {
            active = false;
        }

        @Override
        public StopOutcome requestStop() {
            active = false;
            requestStopCount++;
            return stopOutcomes.isEmpty()
                    ? StopOutcome.CLEAN
                    : stopOutcomes.removeFirst();
        }

        @Override
        public StopOutcome pollStop() {
            pollStopCount++;
            return stopOutcomes.isEmpty()
                    ? StopOutcome.CLEAN
                    : stopOutcomes.removeFirst();
        }

        private void failAt(BlockPosition position) {
            failAt = position;
        }

        private void stopOutcomes(StopOutcome... outcomes) {
            stopOutcomes.addAll(List.of(outcomes));
        }

        private List<BlockPosition> positions() {
            return List.copyOf(positions);
        }

        private int requestStopCount() {
            return requestStopCount;
        }

        private int pollStopCount() {
            return pollStopCount;
        }
    }

    private static final class EmptyDroppedItems
            implements DroppedItemObserverPort {
        @Override
        public List<DroppedItem> observe(BlockPosition center, int radius) {
            return List.of();
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return List.of();
        }
    }

    private static final class FailedTransfers implements ContainerTransferPort {
        @Override
        public ContainerTransferResult startDeposit(
                ContainerTarget target,
                Map<String, Integer> items
        ) {
            return ContainerTransferResult.failed("unused", items);
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            return ContainerTransferResult.failed("unused", Map.of());
        }

        @Override
        public void cancelDeposit() {
        }
    }

    private static final class FailedWithdrawals
            implements ContainerWithdrawalPort {
        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            return ContainerWithdrawalResult.failed("unused", Map.of(), false);
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.failed("unused", Map.of(), false);
        }

        @Override
        public void cancelWithdrawal() {
        }
    }
}
