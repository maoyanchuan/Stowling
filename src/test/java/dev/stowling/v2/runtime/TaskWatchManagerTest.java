package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
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

class TaskWatchManagerTest {
    @Test
    void staysIdleUntilMatchingDroppedItemsAppearThenCoolsDown() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = watchedTask(coordinateId, "");
        FakeDroppedItems droppedItems = new FakeDroppedItems();
        FakeHost host = new FakeHost();
        TaskWatchManager manager = new TaskWatchManager(
                droppedItems,
                new NoopNavigation(),
                host
        );
        manager.refresh(config(task));

        manager.tick();
        assertEquals(0, host.startCount);
        assertEquals(TaskWatchState.IDLE, manager.snapshots().getFirst().state());

        droppedItems.items = List.of(droppedItem("minecraft:diamond"));
        manager.tick();
        assertEquals(1, host.startCount);
        assertEquals(
                TaskWatchState.RUNNING,
                manager.snapshots().getFirst().state()
        );

        host.pending = true;
        manager.tick();
        host.pending = false;
        droppedItems.items = List.of();
        manager.tick();
        assertEquals(
                TaskWatchState.COOLDOWN,
                manager.snapshots().getFirst().state()
        );

        for (int tick = 0; tick < 100; tick++) {
            manager.tick();
        }
        assertEquals(TaskWatchState.IDLE, manager.snapshots().getFirst().state());
    }

    @Test
    void appliesTheDroppedItemFilter() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = watchedTask(
                coordinateId,
                "minecraft:oak_log"
        );
        FakeDroppedItems droppedItems = new FakeDroppedItems(List.of(
                droppedItem("minecraft:birch_log")
        ));
        FakeHost host = new FakeHost();
        TaskWatchManager manager = new TaskWatchManager(
                droppedItems,
                new NoopNavigation(),
                host
        );
        manager.refresh(config(task));

        manager.tick();

        assertEquals(0, host.startCount);
    }

    private static TaskDefinition watchedTask(
            UUID coordinateId,
            String itemFilter
    ) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Drop watch",
                TaskPriority.BACKGROUND,
                false,
                List.of(new TaskStep(
                        UUID.randomUUID(),
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        Map.of(
                                ConfigValidator.COORDINATE_ID,
                                coordinateId.toString()
                        )
                )),
                TaskTriggerDefinition.droppedItems(
                        coordinateId,
                        itemFilter,
                        1,
                        2
                )
        );
    }

    private static AppConfig config(TaskDefinition task) {
        UUID coordinateId = task.trigger().coordinateId();
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
                List.of()
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

    private static final class FakeHost implements TaskWatchManager.Host {
        private int startCount;
        private boolean pending;

        @Override
        public TaskRunSnapshot start(
                TaskDefinition task,
                AppConfig config
        ) {
            startCount++;
            Instant now = Instant.now();
            return new TaskRunSnapshot(
                    UUID.randomUUID(),
                    task.id(),
                    task.name(),
                    TaskPriority.URGENT,
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
        public boolean taskPending(UUID taskId) {
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
