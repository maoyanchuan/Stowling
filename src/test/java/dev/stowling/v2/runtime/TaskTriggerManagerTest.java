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
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskSchedule;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.model.TaskTriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTriggerManagerTest {
    @Test
    void dailyScheduleQueuesOnceAtFourLocalTime() {
        var task = task(UUID.randomUUID());
        var host = new FakeHost();
        host.online = true;
        var clock = new MutableClock("2026-11-21T19:59:59Z");
        var schedule = new TaskSchedule(TaskSchedule.Mode.DAILY, "04:00", "Asia/Shanghai",
                null, null);
        var trigger = TaskTriggerDefinition.scheduled(UUID.randomUUID(), "Daily", task.id(),
                schedule);
        var manager = scheduledManager(task, trigger, clock, host);

        manager.tick();
        assertEquals(Instant.parse("2026-11-21T20:00:00Z"),
                manager.snapshots().getFirst().nextAt());
        clock.set("2026-11-21T20:00:00Z");
        manager.tick();
        manager.tick();
        assertEquals(1, host.startCount);
        assertEquals(TaskPriority.BACKGROUND, host.priority);
        assertEquals(Instant.parse("2026-11-22T20:00:00Z"),
                manager.snapshots().getFirst().nextAt());
    }

    @Test
    void intervalSurvivesRestartAndSkipsOfflineOrBusyOccurrences() {
        var task = task(UUID.randomUUID());
        var host = new FakeHost();
        var clock = new MutableClock("2026-11-22T00:17:00Z");
        var schedule = new TaskSchedule(TaskSchedule.Mode.INTERVAL, null, null, 60, null)
                .activate(null, clock.instant());
        var trigger = TaskTriggerDefinition.scheduled(UUID.randomUUID(), "Hourly", task.id(),
                schedule);
        var manager = scheduledManager(task, trigger, clock, host);
        clock.set("2026-11-22T01:17:00Z");
        manager.tick();
        assertEquals(0, host.startCount);

        clock.set("2026-11-22T02:01:00Z");
        manager = scheduledManager(task, trigger, clock, host);
        assertEquals(Instant.parse("2026-11-22T02:17:00Z"),
                manager.snapshots().getFirst().nextAt());
        host.online = true;
        host.pending = true;
        manager.tick();
        clock.set("2026-11-22T02:17:00Z");
        manager.tick();
        host.pending = false;
        manager.tick();
        assertEquals(0, host.startCount);

        clock.set("2026-11-22T03:17:00Z");
        manager.tick();
        assertEquals(1, host.startCount);
    }

    private static TaskTriggerManager scheduledManager(TaskDefinition task,
            TaskTriggerDefinition trigger, Clock clock, FakeHost host) {
        var manager = new TaskTriggerManager(new FakeDroppedItems(), new FakeFood(20),
                new NoopNavigation(), host, clock);
        manager.refresh(config(task, trigger));
        return manager;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(String value) {
            set(value);
        }

        void set(String value) {
            now = Instant.parse(value);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }
    }

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
    void backpackMealListenerWaitsForProtectedFood() {
        String food = "minecraft:bread";
        TaskDefinition task = task(UUID.randomUUID());
        var trigger = new TaskTriggerDefinition(UUID.randomUUID(), "Meal",
                TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW, task.id(), TaskPriority.URGENT,
                1, 0, null, null, 18, null, null, null, food);
        FakeHost host = new FakeHost();
        TaskTriggerManager manager = new TaskTriggerManager(new FakeDroppedItems(),
                new FakeFood(18), new NoopNavigation(), host);
        manager.refresh(config(task, trigger));
        manager.tick();
        assertEquals(0, host.startCount);

        host.protectedFood = food;
        manager.tick();
        assertEquals(1, host.startCount);
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

    @Test
    void cacheEventTriggerCoalescesChangesWhileTheSortTaskIsRunning() {
        UUID coordinateId = UUID.randomUUID();
        UUID regionId = UUID.randomUUID();
        TaskDefinition task = task(coordinateId);
        TaskTriggerDefinition trigger = TaskTriggerDefinition.cacheUpdated(
                UUID.randomUUID(),
                "Sort cache",
                task.id(),
                TaskPriority.NORMAL,
                1,
                2,
                regionId
        );
        FakeHost host = new FakeHost();
        TaskTriggerManager manager = new TaskTriggerManager(
                new FakeDroppedItems(),
                new FakeFood(20),
                new NoopNavigation(),
                host
        );
        manager.refresh(config(
                task,
                trigger,
                List.of(new RegionDefinition(
                        regionId,
                        "Cache",
                        0,
                        64,
                        0,
                        10,
                        70,
                        10
                ))
        ));

        manager.tick();
        assertEquals(0, host.startCount);

        manager.cacheUpdated(regionId);
        manager.tick();
        assertEquals(1, host.startCount);

        host.pending = true;
        manager.tick();
        manager.cacheUpdated(regionId);
        host.pending = false;
        manager.tick();
        for (int tick = 0; tick < 3; tick++) {
            manager.tick();
        }

        assertEquals(2, host.startCount);
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
        return config(task, trigger, List.of());
    }

    private static AppConfig config(
            TaskDefinition task,
            TaskTriggerDefinition trigger,
            List<RegionDefinition> regions
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
                regions,
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
        private boolean online;
        private UUID taskId;
        private TaskPriority priority;
        private String protectedFood;

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

        @Override
        public boolean canRunScheduledTasks() {
            return online;
        }

        @Override
        public boolean hasProtectedFood(String itemId) {
            return itemId.equals(protectedFood);
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
