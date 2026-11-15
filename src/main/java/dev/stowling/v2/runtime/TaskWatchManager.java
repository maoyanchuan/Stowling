package dev.stowling.v2.runtime;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TaskWatchManager {
    private static final int DEFAULT_RADIUS = 16;

    interface Host {
        TaskRunSnapshot start(TaskDefinition task, AppConfig config);

        boolean taskPending(UUID taskId);
    }

    private final DroppedItemObserverPort droppedItems;
    private final NavigationPort navigation;
    private final Host host;
    private final Map<UUID, WatchRuntime> watches = new LinkedHashMap<>();
    private long ticks;

    TaskWatchManager(
            DroppedItemObserverPort droppedItems,
            NavigationPort navigation,
            Host host
    ) {
        this.droppedItems = Objects.requireNonNull(
                droppedItems,
                "droppedItems"
        );
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.host = Objects.requireNonNull(host, "host");
    }

    void refresh(AppConfig config) {
        Objects.requireNonNull(config, "config");
        Map<UUID, CoordinatePoint> coordinates = new LinkedHashMap<>();
        for (CoordinatePoint point : config.coordinates()) {
            coordinates.put(point.id(), point);
        }
        Map<UUID, WatchConfiguration> next = new LinkedHashMap<>();
        for (TaskDefinition task : config.tasks()) {
            if (task.trigger() == null) {
                continue;
            }
            CoordinatePoint coordinate = coordinates.get(
                    task.trigger().coordinateId()
            );
            if (coordinate == null) {
                continue;
            }
            next.put(task.id(), new WatchConfiguration(
                    task,
                    coordinate,
                    config
            ));
        }
        watches.keySet().removeIf(taskId -> !next.containsKey(taskId));
        for (Map.Entry<UUID, WatchConfiguration> entry : next.entrySet()) {
            WatchRuntime existing = watches.get(entry.getKey());
            WatchConfiguration replacement = entry.getValue();
            if (existing == null
                    || !existing.configuration.task.equals(
                    replacement.task
            )) {
                watches.put(
                        entry.getKey(),
                        new WatchRuntime(replacement, ticks)
                );
            } else {
                existing.configuration = replacement;
            }
        }
    }

    void tick() {
        ticks++;
        for (WatchRuntime watch : watches.values()) {
            synchronize(watch);
        }
        for (WatchRuntime watch : watches.values()) {
            if ((watch.state != TaskWatchState.IDLE
                    && watch.state != TaskWatchState.ERROR)
                    || ticks < watch.nextCheckTick) {
                continue;
            }
            observe(watch);
        }
    }

    List<TaskWatchSnapshot> snapshots() {
        List<TaskWatchSnapshot> result = new ArrayList<>();
        for (WatchRuntime watch : watches.values()) {
            TaskTriggerDefinition trigger =
                    watch.configuration.task.trigger();
            result.add(new TaskWatchSnapshot(
                    watch.configuration.task.id(),
                    watch.configuration.task.name(),
                    watch.configuration.coordinate.id(),
                    watch.configuration.coordinate.name(),
                    trigger.itemFilter(),
                    watch.state,
                    watch.message,
                    watch.matchedDrops
            ));
        }
        result.sort(Comparator.comparing(TaskWatchSnapshot::taskName));
        return List.copyOf(result);
    }

    private void synchronize(WatchRuntime watch) {
        if (host.taskPending(watch.configuration.task.id())) {
            watch.state = TaskWatchState.RUNNING;
            watch.message = "该任务正在排队或执行";
            return;
        }
        if (watch.state == TaskWatchState.RUNNING) {
            startCooldown(watch);
            return;
        }
        if (watch.state == TaskWatchState.COOLDOWN
                && ticks >= watch.cooldownUntilTick) {
            watch.state = TaskWatchState.IDLE;
            watch.matchedDrops = 0;
            watch.nextCheckTick = ticks;
            watch.message = idleMessage(watch.configuration.task.trigger());
        }
    }

    private void observe(WatchRuntime watch) {
        TaskTriggerDefinition trigger =
                watch.configuration.task.trigger();
        watch.nextCheckTick = ticks + trigger.intervalTicks();
        CoordinatePoint coordinate = watch.configuration.coordinate;
        if (coordinate.dimension() != null
                && !navigation.isInDimension(coordinate.dimension())) {
            watch.state = TaskWatchState.ERROR;
            watch.message = "等待进入坐标所在维度";
            return;
        }
        BlockPosition center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        int radius = coordinate.radius() == null
                ? DEFAULT_RADIUS
                : coordinate.radius();
        try {
            List<DroppedItem> matches = droppedItems
                    .observe(center, radius)
                    .stream()
                    .filter(item -> trigger.matches(item.itemId()))
                    .toList();
            if (matches.isEmpty()) {
                watch.state = TaskWatchState.IDLE;
                watch.matchedDrops = 0;
                watch.message = idleMessage(trigger);
                return;
            }
            watch.matchedDrops = matches.size();
            host.start(
                    watch.configuration.task,
                    watch.configuration.config
            );
            watch.state = TaskWatchState.RUNNING;
            watch.message = "检测到 " + matches.size()
                    + " 组掉落物，已提交紧急任务";
        } catch (RuntimeException exception) {
            watch.state = TaskWatchState.ERROR;
            watch.matchedDrops = 0;
            watch.message = safeMessage(exception);
        }
    }

    private void startCooldown(WatchRuntime watch) {
        int cooldown = watch.configuration.task.trigger().cooldownTicks();
        watch.cooldownUntilTick = ticks + cooldown;
        watch.nextCheckTick = watch.cooldownUntilTick;
        watch.state = cooldown == 0
                ? TaskWatchState.IDLE
                : TaskWatchState.COOLDOWN;
        watch.message = cooldown == 0
                ? idleMessage(watch.configuration.task.trigger())
                : "任务结束后冷却 "
                        + String.format("%.1f", cooldown / 20.0)
                        + " 秒";
    }

    private static String idleMessage(TaskTriggerDefinition trigger) {
        String filter = trigger.itemFilter() == null
                ? "全部物品"
                : trigger.itemFilter();
        return "空闲监听 · " + filter;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static final class WatchConfiguration {
        private final TaskDefinition task;
        private final CoordinatePoint coordinate;
        private final AppConfig config;

        private WatchConfiguration(
                TaskDefinition task,
                CoordinatePoint coordinate,
                AppConfig config
        ) {
            this.task = task;
            this.coordinate = coordinate;
            this.config = config;
        }
    }

    private static final class WatchRuntime {
        private WatchConfiguration configuration;
        private TaskWatchState state = TaskWatchState.IDLE;
        private String message;
        private int matchedDrops;
        private long nextCheckTick;
        private long cooldownUntilTick;

        private WatchRuntime(
                WatchConfiguration configuration,
                long currentTick
        ) {
            this.configuration = configuration;
            this.nextCheckTick = currentTick;
            this.message = idleMessage(configuration.task.trigger());
        }
    }
}
