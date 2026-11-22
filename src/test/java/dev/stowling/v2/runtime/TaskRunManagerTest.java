package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskRunManagerTest {
    @Test
    void followUpCountdownStartsAfterSuccessAndDoesNotOccupyTheRobot() {
        UUID point = UUID.randomUUID();
        TaskDefinition next = task("Next task", TaskPriority.URGENT, false, step(point));
        TaskDefinition source = task("Pickup", TaskPriority.NORMAL, false, step(point),
                TaskStep.createTask(UUID.randomUUID(), Map.of(
                        ConfigValidator.TASK_REF, "task:" + next.id(),
                        ConfigValidator.CREATE_AFTER_SUCCESS, "true",
                        ConfigValidator.CREATE_DELAY_SECONDS, "30")));
        TaskDefinition other = task("Other task", TaskPriority.NORMAL, false, step(point));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, coordinates(point),
                List.of(source, next, other));
        var navigation = new RecordingNavigation(NavigationStatus.ARRIVED,
                NavigationStatus.ARRIVED, NavigationStatus.ARRIVED);
        var clock = new MutableClock();
        var manager = new TaskRunManager(navigation,
                TaskModuleRuntimeRegistry.unavailablePorts(navigation),
                dev.stowling.automation.WorldObservation::disconnected, clock);
        var run = manager.start(source, config, null);
        for (int tick = 0; tick < 8; tick++) manager.tick();

        assertEquals(TaskRunState.SUCCEEDED, manager.runs().stream()
                .filter(item -> item.runId().equals(run.runId())).findFirst().orElseThrow().state());
        assertFalse(manager.busy());
        manager.start(other, config, null);
        for (int tick = 0; tick < 8; tick++) manager.tick();
        assertTrue(manager.runs().stream().anyMatch(item -> item.taskId().equals(other.id())
                && item.state() == TaskRunState.SUCCEEDED));
        clock.advance(Duration.ofSeconds(29));
        manager.tick();
        assertFalse(manager.runs().stream().anyMatch(item -> item.taskId().equals(next.id())));
        clock.advance(Duration.ofSeconds(1));
        manager.tick();
        assertEquals(1, manager.runs().stream().filter(item -> item.taskId().equals(next.id())).count());
        for (int tick = 0; tick < 8; tick++) manager.tick();
        assertEquals(1, manager.runs().stream().filter(item -> item.taskId().equals(next.id())).count());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    @Test
    void createsTheSelectedUrgentTaskOnlyAfterTheSourceSucceeds() {
        for (NavigationStatus status : List.of(NavigationStatus.ARRIVED, NavigationStatus.FAILED)) {
            UUID point = UUID.randomUUID();
            TaskDefinition next = task("Next task", TaskPriority.URGENT, false, step(point));
            TaskDefinition pickup = task("Pickup", TaskPriority.NORMAL, false, step(point),
                    TaskStep.createTask(UUID.randomUUID(), Map.of(
                            ConfigValidator.TASK_REF, "task:" + next.id(),
                            ConfigValidator.CREATE_AFTER_SUCCESS, "true")));
            AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, coordinates(point),
                    List.of(pickup, next));
            var manager = new TaskRunManager(new RecordingNavigation(status, NavigationStatus.ARRIVED));
            var run = manager.start(pickup, config, null);

            for (int tick = 0; tick < 8; tick++) manager.tick();

            var completed = manager.runs().stream().filter(item -> item.runId().equals(run.runId()))
                    .findFirst().orElseThrow();
            boolean succeeded = status == NavigationStatus.ARRIVED;
            assertEquals(succeeded ? TaskRunState.SUCCEEDED : TaskRunState.FAILED,
                    completed.state());
            assertEquals(succeeded, manager.runs().stream()
                    .anyMatch(item -> item.taskId().equals(next.id())));
        }
    }

    @Test
    void runsLinearMovementStepsInOrderAndCoalescesDuplicateRequests() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        TaskDefinition task = task(step(firstId), step(secondId));
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED,
                NavigationStatus.ARRIVED
        );
        TaskRunManager manager = new TaskRunManager(navigation);

        TaskRunSnapshot first = manager.start(
                task,
                coordinates(firstId, secondId)
        );
        TaskRunSnapshot duplicate = manager.start(
                task,
                coordinates(firstId, secondId)
        );
        assertEquals(first.runId(), duplicate.runId());
        for (int tick = 0; tick < 4; tick++) {
            manager.tick();
        }

        assertEquals(
                List.of(
                        new BlockPosition(0, 64, 0),
                        new BlockPosition(10, 64, 10)
                ),
                navigation.positions()
        );
        assertEquals(TaskRunState.SUCCEEDED, manager.runs().getFirst().state());
        assertEquals(2, manager.runs().getFirst().completedSteps());
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void higherPriorityPreemptsAndRestartsInterruptedTaskFromFirstStep() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition background = task(
                "Classify",
                TaskPriority.BACKGROUND,
                false,
                step(coordinateId)
        );
        TaskDefinition urgent = task(
                "Emergency",
                TaskPriority.URGENT,
                false,
                step(coordinateId)
        );
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED,
                NavigationStatus.ARRIVED
        );
        TaskRunManager manager = new TaskRunManager(navigation);

        TaskRunSnapshot firstBackground = manager.start(
                background,
                coordinates(coordinateId)
        );
        manager.tick();
        TaskRunSnapshot urgentRun = manager.start(
                urgent,
                coordinates(coordinateId)
        );
        assertEquals(TaskRunState.QUEUED, urgentRun.state());

        for (int tick = 0; tick < 7; tick++) {
            manager.tick();
        }

        assertEquals(
                List.of(
                        new BlockPosition(0, 64, 0),
                        new BlockPosition(0, 64, 0),
                        new BlockPosition(0, 64, 0)
                ),
                navigation.positions()
        );
        assertEquals(
                TaskRunState.SUCCEEDED,
                manager.runs().stream()
                        .filter(run -> run.runId().equals(urgentRun.runId()))
                        .findFirst()
                        .orElseThrow()
                        .state()
        );
        assertEquals(
                TaskRunState.PREEMPTED,
                manager.runs().stream()
                        .filter(run -> run.runId()
                                .equals(firstBackground.runId()))
                        .findFirst()
                        .orElseThrow()
                        .state()
        );
        assertTrue(manager.runs().stream().anyMatch(
                run -> run.taskId().equals(background.id())
                        && !run.runId().equals(firstBackground.runId())
                        && run.state() == TaskRunState.SUCCEEDED
        ));
    }

    @Test
    void lowerPriorityTaskWaitsWithoutInterruptingActiveRun() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition normal = task(
                "Normal",
                TaskPriority.NORMAL,
                false,
                step(coordinateId)
        );
        TaskDefinition background = task(
                "Background",
                TaskPriority.BACKGROUND,
                false,
                step(coordinateId)
        );
        TaskRunManager manager = new TaskRunManager(
                new RecordingNavigation(
                        NavigationStatus.ARRIVED,
                        NavigationStatus.ARRIVED
                )
        );

        manager.start(normal, coordinates(coordinateId));
        manager.tick();
        TaskRunSnapshot backgroundRun = manager.start(
                background,
                coordinates(coordinateId)
        );

        assertEquals(TaskRunState.QUEUED, backgroundRun.state());
        assertEquals(
                TaskRunState.RUNNING,
                manager.status().orElseThrow().state()
        );
        assertEquals(1, manager.queued().size());
    }

    @Test
    void backgroundTaskRequeuesAfterCompletionCooldown() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = task(
                "Monitor",
                TaskPriority.BACKGROUND,
                true,
                step(coordinateId)
        );
        TaskRunManager manager = new TaskRunManager(
                new RecordingNavigation(NavigationStatus.ARRIVED)
        );

        TaskRunSnapshot firstRun = manager.start(
                task,
                coordinates(coordinateId)
        );
        manager.tick();
        manager.tick();

        assertEquals(
                TaskRunState.SUCCEEDED,
                manager.runs().stream()
                        .filter(run -> run.runId().equals(firstRun.runId()))
                        .findFirst()
                        .orElseThrow()
                        .state()
        );
        assertEquals(1, manager.queued().size());
        for (int tick = 0; tick < 100; tick++) {
            manager.tick();
        }
        assertTrue(manager.status().isPresent());
    }

    @Test
    void failsTheRunWhenNavigationFails() {
        UUID coordinateId = UUID.randomUUID();
        TaskRunManager manager = new TaskRunManager(
                new RecordingNavigation(NavigationStatus.FAILED)
        );

        manager.start(task(step(coordinateId)), coordinates(coordinateId));
        manager.tick();
        manager.tick();

        assertEquals(TaskRunState.FAILED, manager.runs().getFirst().state());
        assertEquals(0, manager.runs().getFirst().completedSteps());
    }

    @Test
    void laterStepCreationFailurePreservesCompletedProgress() {
        UUID coordinateId = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED
        );
        TaskRunManager manager = new TaskRunManager(navigation);
        TaskStep broken = TaskStep.action(
                UUID.randomUUID(),
                "UNKNOWN_TEST_ACTION",
                Map.of()
        );
        TaskRunSnapshot run = manager.start(
                task(step(coordinateId), broken),
                coordinates(coordinateId)
        );

        manager.tick();
        manager.tick();

        TaskRunSnapshot failed = manager.runs().stream()
                .filter(candidate -> candidate.runId().equals(run.runId()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskRunState.FAILED, failed.state());
        assertEquals(1, failed.completedSteps());
        assertEquals(2, failed.totalSteps());
        assertTrue(failed.message().contains("第 2 步（UNKNOWN_TEST_ACTION）"));
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void cancelsOnlyTheActiveRun() {
        UUID coordinateId = UUID.randomUUID();
        TaskRunManager manager = new TaskRunManager(
                new RecordingNavigation(NavigationStatus.ARRIVED)
        );
        TaskRunSnapshot run = manager.start(
                task(step(coordinateId)),
                coordinates(coordinateId)
        );

        manager.tick();
        manager.cancel(run.runId());
        manager.tick();

        assertEquals(TaskRunState.CANCELLED, manager.runs().getFirst().state());
        assertTrue(manager.status().isEmpty());
        assertThrows(
                TaskRunNotFoundException.class,
                () -> manager.cancel(run.runId())
        );
    }

    @Test
    void freezesCoordinatesForTheCurrentRun() {
        UUID coordinateId = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED,
                NavigationStatus.ARRIVED
        );
        TaskRunManager manager = new TaskRunManager(navigation);
        TaskDefinition task = task(step(coordinateId));

        manager.start(
                task,
                List.of(new CoordinatePoint(
                        coordinateId,
                        "Original",
                        0,
                        64,
                        0
                ))
        );
        manager.tick();
        manager.tick();
        manager.start(
                task,
                List.of(new CoordinatePoint(
                        coordinateId,
                        "Moved",
                        30,
                        70,
                        -20
                ))
        );
        manager.tick();
        manager.tick();

        assertEquals(
                List.of(
                        new BlockPosition(0, 64, 0),
                        new BlockPosition(30, 70, -20)
                ),
                navigation.positions()
        );
    }

    @Test
    void failsWhenConfiguredDimensionDoesNotMatch() {
        UUID coordinateId = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED
        ).rejectingDimensions();
        TaskRunManager manager = new TaskRunManager(navigation);
        CoordinatePoint target = new CoordinatePoint(
                coordinateId,
                "Nether",
                0,
                64,
                0,
                "minecraft:the_nether",
                null
        );

        manager.start(task(step(coordinateId)), List.of(target));
        manager.tick();

        assertEquals(TaskRunState.FAILED, manager.runs().getFirst().state());
        assertTrue(navigation.positions().isEmpty());
    }

    @Test
    void passesConfiguredRadiusToNavigation() {
        UUID coordinateId = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED
        );
        TaskRunManager manager = new TaskRunManager(navigation);
        CoordinatePoint target = new CoordinatePoint(
                coordinateId,
                "Nearby",
                0,
                64,
                0,
                null,
                7
        );

        manager.start(task(step(coordinateId)), List.of(target));
        manager.tick();
        manager.tick();

        assertEquals(List.of(7), navigation.radii());
    }

    private static TaskDefinition task(TaskStep... steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Travel",
                List.of(steps)
        );
    }

    private static TaskDefinition task(
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            TaskStep... steps
    ) {
        return new TaskDefinition(
                UUID.randomUUID(),
                name,
                priority,
                repeatWhenIdle,
                List.of(steps),
                null,
                null,
                false,
                true
        );
    }

    private static TaskStep step(UUID coordinateId) {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.COORDINATE_ID, coordinateId.toString())
        );
    }

    private static List<CoordinatePoint> coordinates(UUID first, UUID second) {
        return List.of(
                new CoordinatePoint(first, "First", 0, 64, 0),
                new CoordinatePoint(second, "Second", 10, 64, 10)
        );
    }

    private static List<CoordinatePoint> coordinates(UUID coordinateId) {
        return List.of(new CoordinatePoint(
                coordinateId,
                "Target",
                0,
                64,
                0
        ));
    }

    private static final class RecordingNavigation implements NavigationPort {
        private final Deque<NavigationStatus> statuses;
        private final List<BlockPosition> positions = new ArrayList<>();
        private final List<Integer> radii = new ArrayList<>();
        private boolean active;
        private boolean dimensionsAllowed = true;

        private RecordingNavigation(NavigationStatus... statuses) {
            this.statuses = new ArrayDeque<>(List.of(statuses));
        }

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
            if (!active || statuses.isEmpty()) {
                return NavigationStatus.FAILED;
            }
            radii.add(radius);
            NavigationStatus status = statuses.removeFirst();
            if (status != NavigationStatus.NAVIGATING) {
                active = false;
            }
            return status;
        }

        @Override
        public boolean isInDimension(String dimension) {
            return dimensionsAllowed;
        }

        @Override
        public void cancel() {
            active = false;
        }

        @Override
        public StopOutcome requestStop() {
            active = false;
            return StopOutcome.CLEAN;
        }

        private List<BlockPosition> positions() {
            return List.copyOf(positions);
        }

        private List<Integer> radii() {
            return List.copyOf(radii);
        }

        private RecordingNavigation rejectingDimensions() {
            dimensionsAllowed = false;
            return this;
        }
    }
}
