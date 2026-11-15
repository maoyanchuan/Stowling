package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
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
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTriggerManagerTest {
    @Test
    void droppedItemTriggerQueuesTheSelectedTaskAtItsPriority() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = task(coordinateId);
        TaskTriggerDefinition trigger = TaskTriggerDefinition.droppedItems(
                UUID.randomUUID(),
                "Pickup drops",
                task.id(),
                TaskPriority.NORMAL,
                1,
                2,
                coordinateId,
                "minecraft:oak_log"
        );
        FakeDroppedItems droppedItems = new FakeDroppedItems();
        FakeHost host = new FakeHost();
        TaskTriggerManager manager = new TaskTriggerManager(
                droppedItems,
                new FakeFood(20),
                new NoopNavigation(),
                host
        );
        manager.refresh(config(task, trigger));

        manager.tick();
        assertEquals(0, host.startCount);
        assertEquals(
                TaskTriggerState.IDLE,
                manager.snapshots().getFirst().state()
        );

        droppedItems.items = List.of(droppedItem("minecraft:oak_log"));
        manager.tick();
        assertEquals(1, host.startCount);
        assertEquals(TaskPriority.NORMAL, host.priority);
        assertEquals(task.id(), host.taskId);
        assertEquals(
                TaskTriggerState.RUNNING,
                manager.snapshots().getFirst().state()
        );

        host.pending = true;
        manager.tick();
        host.pending = false;
        droppedItems.items = List.of();
        manager.tick();
        assertEquals(
                TaskTriggerState.COOLDOWN,
                manager.snapshots().getFirst().state()
        );
    }

    @Test
    void foodTriggerFiresAtOrBelowTheConfiguredLevel() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = task(coordinateId);
        TaskTriggerDefinition trigger =
                TaskTriggerDefinition.foodLevelAtOrBelow(
                        UUID.randomUUID(),
                        "Eat food",
                        task.id(),
                        TaskPriority.NORMAL,
                        1,
                        0,
                        18
                );
        FakeHost host = new FakeHost();
        TaskTriggerManager manager = new TaskTriggerManager(
                new FakeDroppedItems(),
                new FakeFood(19),
                new NoopNavigation(),
                host
        );
        manager.refresh(config(task, trigger));

        manager.tick();
        assertEquals(0, host.startCount);

        manager = new TaskTriggerManager(
                new FakeDroppedItems(),
                new FakeFood(18),
                new NoopNavigation(),
                host
        );
        manager.refresh(config(task, trigger));
        manager.tick();

        assertEquals(1, host.startCount);
        assertEquals(TaskPriority.NORMAL, host.priority);
    }

    @Test
    void droppedItemFilterPreventsUnrelatedTasksFromStarting() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = task(coordinateId);
        TaskTriggerDefinition trigger = TaskTriggerDefinition.droppedItems(
                UUID.randomUUID(),
                "Oak only",
                task.id(),
                TaskPriority.URGENT,
                1,
                0,
                coordinateId,
                "minecraft:oak_log"
        );
        FakeDroppedItems droppedItems = new FakeDroppedItems(List.of(
                droppedItem("minecraft:birch_log")
        ));
        FakeHost host = new FakeHost();
        TaskTriggerManager manager = new TaskTriggerManager(
                droppedItems,
                new FakeFood(20),
                new NoopNavigation(),
                host
        );
        manager.refresh(config(task, trigger));

        manager.tick();

        assertEquals(0, host.startCount);
    }

    private static TaskDefinition task(UUID coordinateId) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Pickup",
                TaskPriority.BACKGROUND,
                false,
                List.of(new TaskStep(
                        UUID.randomUUID(),
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        Map.of(
                                ConfigValidator.COORDINATE_ID,
                                coordinateId.toString()
                        )
                ))
        );
    }

    private static AppConfig config(
            TaskDefinition task,
            TaskTriggerDefinition trigger
    ) {
        UUID coordinateId = UUID.fromString(
                task.steps().getFirst()
                        .parameters()
                        .get(ConfigValidator.COORDINATE_ID)
        );
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(new CoordinatePoint(
                        coordinateId,
                        "Drops",
                        0,
                        64,
                        0,
                        null,
                        4
                )),
                List.of(task),
                List.of(),
                List.of(),
                List.of(trigger)
        );
    }

    private static DroppedItem droppedItem(String itemId) {
        return new DroppedItem(
                UUID.randomUUID(),
                itemId,
                1,
                new BlockPosition(0, 64, 0)
        );
    }

    private static final class FakeDroppedItems
            implements DroppedItemObserverPort {
        private List<DroppedItem> items = List.of();

        private FakeDroppedItems() {
        }

        private FakeDroppedItems(List<DroppedItem> items) {
            this.items = List.copyOf(items);
        }

        @Override
        public List<DroppedItem> observe(
                BlockPosition center,
                int radius
        ) {
            return List.copyOf(items);
        }

        @Override
        public List<DroppedItem> observe(
                dev.stowling.v2.model.RegionDefinition region
        ) {
            return List.of();
        }
    }

    private static final class FakeFood implements FoodConsumptionPort {
        private FoodStatus status;

        private FakeFood(int foodLevel) {
            this.status = new FoodStatus(true, foodLevel, 0.0F);
        }

        @Override
        public FoodStatus observe() {
            return status;
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            return FoodConsumptionResult.failed("unused");
        }

        @Override
        public FoodConsumptionResult pollEating() {
            return FoodConsumptionResult.failed("unused");
        }

        @Override
        public void cancelEating() {
        }
    }

    private static final class FakeHost implements TaskTriggerManager.Host {
        private int startCount;
        private boolean pending;
        private UUID taskId;
        private TaskPriority priority;

        @Override
        public TaskRunSnapshot start(
                TaskDefinition task,
                TaskPriority runPriority,
                AppConfig config
        ) {
            startCount++;
            taskId = task.id();
            priority = runPriority;
            Instant now = Instant.now();
            return new TaskRunSnapshot(
                    UUID.randomUUID(),
                    task.id(),
                    task.name(),
                    runPriority,
                    false,
                    TaskRunState.RUNNING,
                    0,
                    task.steps().size(),
                    "running",
                    now,
                    now
            );
        }

        @Override
        public boolean taskPending(UUID ignoredTaskId) {
            return pending;
        }
    }

    private static final class NoopNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return StopOutcome.CLEAN;
        }
    }
}
