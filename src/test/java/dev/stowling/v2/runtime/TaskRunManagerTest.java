package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskRunManagerTest {
    @Test
    void runsLinearMovementStepsInOrderAndRejectsOverlap() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        TaskDefinition task = task(
                step(firstId),
                step(secondId)
        );
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.ARRIVED,
                NavigationStatus.ARRIVED
        );
        TaskRunManager manager = new TaskRunManager(navigation);

        manager.start(task, coordinates(firstId, secondId));
        assertThrows(
                TaskRunBusyException.class,
                () -> manager.start(task, coordinates(firstId, secondId))
        );
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
    void failsTheRunWhenNavigationFails() {
        UUID coordinateId = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation(
                NavigationStatus.FAILED
        );
        TaskRunManager manager = new TaskRunManager(navigation);

        manager.start(task(step(coordinateId)), coordinates(coordinateId));
        manager.tick();
        manager.tick();

        assertEquals(TaskRunState.FAILED, manager.runs().getFirst().state());
        assertEquals(0, manager.runs().getFirst().completedSteps());
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

    private static TaskDefinition task(TaskStep... steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Travel",
                List.of(steps)
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
        private boolean active;

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
            if (!active || statuses.isEmpty()) {
                return NavigationStatus.FAILED;
            }
            NavigationStatus status = statuses.removeFirst();
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
            return StopOutcome.CLEAN;
        }

        private List<BlockPosition> positions() {
            return List.copyOf(positions);
        }
    }
}
