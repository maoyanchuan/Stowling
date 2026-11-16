package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import dev.stowling.v2.model.TaskInteractionCompiler;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskInteractionRuntimeTest {
    @Test
    void refreshAllowsUnboundDetectorPlaceholder() {
        TaskDefinition task = task("Collect", interaction(
                "COLLECT",
                Map.of()
        ));
        AppConfig config = config(List.of(), List.of(task));
        TaskRunManager manager = new TaskRunManager(new TestNavigation());

        assertDoesNotThrow(() -> manager.refreshTriggers(config));
        assertThrows(
                TaskInteractionCompiler.DetectorNotBoundException.class,
                () -> manager.start(task, config, null)
        );
    }

    @Test
    void parentCreateTaskCompilesChildBeforeQueueing() {
        UUID coordinateId = UUID.randomUUID();
        CoordinatePoint coordinate = new CoordinatePoint(
                coordinateId,
                "Read",
                3,
                64,
                3
        );
        TaskDefinition child = task("Child", interaction(
                "READ",
                Map.of(ConfigValidator.TARGET_REF, "coordinate:" + coordinateId)
        ));
        TaskDefinition parent = task(
                "Parent",
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of(ConfigValidator.TASK_REF, "task:" + child.id())
                )
        );
        AppConfig config = config(
                List.of(coordinate),
                List.of(parent, child)
        );
        TaskRunManager manager = new TaskRunManager(new TestNavigation());

        manager.start(parent, config, null);
        manager.tick();

        TaskRunSnapshot startedChild = manager.status().orElseThrow();
        assertEquals(child.id(), startedChild.taskId());
        assertEquals(2, startedChild.totalSteps());
    }

    @Test
    void defaultInterruptionFlowIsCompiledBeforeExecution() {
        UUID mainCoordinateId = UUID.randomUUID();
        UUID cleanupCoordinateId = UUID.randomUUID();
        TaskDefinition main = task(
                "Main",
                moveStep(mainCoordinateId)
        );
        TaskDefinition cleanup = new TaskDefinition(
                UUID.randomUUID(),
                "Default cleanup",
                TaskPriority.NORMAL,
                false,
                List.of(interaction(
                        "READ",
                        Map.of(
                                ConfigValidator.TARGET_REF,
                                "coordinate:" + cleanupCoordinateId
                        )
                )),
                TaskInterruptionMode.NONE,
                null,
                true,
                false
        );
        List<CoordinatePoint> coordinates = List.of(
                new CoordinatePoint(
                        mainCoordinateId,
                        "Main",
                        0,
                        64,
                        0
                ),
                new CoordinatePoint(
                        cleanupCoordinateId,
                        "Cleanup",
                        1,
                        64,
                        1
                )
        );
        AppConfig config = config(coordinates, List.of(main, cleanup));
        TestNavigation navigation = new TestNavigation();
        TaskRunManager manager = new TaskRunManager(navigation);

        TaskRunSnapshot run = manager.start(main, config, null);
        manager.tick();
        manager.cancel(run.runId());
        for (int tick = 0; tick < 8; tick++) {
            manager.tick();
        }

        assertTrue(navigation.positions.contains(
                new BlockPosition(1, 64, 1)
        ));
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

    private static TaskDefinition task(String name, TaskStep... steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                name,
                TaskPriority.NORMAL,
                false,
                List.of(steps)
        );
    }

    private static TaskStep moveStep(UUID coordinateId) {
        return TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.TARGET_REF,
                        "coordinate:" + coordinateId)
        );
    }

    private static TaskStep interaction(
            String operation,
            Map<String, String> parameters
    ) {
        Map<String, String> values = new LinkedHashMap<>(parameters);
        values.put("operation", operation);
        return TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.CONTAINER_INTERACTION,
                values
        );
    }

    private static final class TestNavigation implements NavigationPort {
        private final List<BlockPosition> positions = new ArrayList<>();
        private boolean active;

        @Override
        public NavigationStatus start(BlockPosition target) {
            positions.add(target);
            active = true;
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return active
                    ? NavigationStatus.NAVIGATING
                    : NavigationStatus.FAILED;
        }

        @Override
        public boolean isInDimension(String dimension) {
            return true;
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
    }
}
