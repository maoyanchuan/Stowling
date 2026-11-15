package dev.stowling.v2.runtime;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodStatus;
import dev.stowling.automation.NavigationPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.model.TaskTriggerType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TaskTriggerManager {
    private static final int DEFAULT_RADIUS = 16;

    interface Host {
        TaskRunSnapshot start(
                TaskDefinition task,
                TaskPriority priority,
                AppConfig config
        );

        boolean taskPending(UUID taskId);
    }

    private final DroppedItemObserverPort droppedItems;
    private final FoodConsumptionPort foodConsumption;
    private final NavigationPort navigation;
    private final Host host;
    private final Map<UUID, TriggerRuntime> triggers = new LinkedHashMap<>();
    private long ticks;

    TaskTriggerManager(
            DroppedItemObserverPort droppedItems,
            FoodConsumptionPort foodConsumption,
            NavigationPort navigation,
            Host host
    ) {
        this.droppedItems = Objects.requireNonNull(
                droppedItems,
                "droppedItems"
        );
        this.foodConsumption = Objects.requireNonNull(
                foodConsumption,
                "foodConsumption"
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
        Map<UUID, TaskDefinition> tasks = new LinkedHashMap<>();
        for (TaskDefinition task : config.tasks()) {
            tasks.put(task.id(), task);
        }
        Map<UUID, RegionDefinition> regions = new LinkedHashMap<>();
        for (RegionDefinition region : config.regions()) {
            regions.put(region.id(), region);
        }
        Map<UUID, TriggerConfiguration> next = new LinkedHashMap<>();
        for (TaskTriggerDefinition trigger : config.triggers()) {
            TaskDefinition target = tasks.get(trigger.targetTaskId());
            if (target == null) {
                continue;
            }
            CoordinatePoint coordinate = trigger.coordinateId() == null
                    ? null
                    : coordinates.get(trigger.coordinateId());
            RegionDefinition cacheRegion = trigger.cacheRegionId() == null
                    ? null
                    : regions.get(trigger.cacheRegionId());
            next.put(trigger.id(), new TriggerConfiguration(
                    trigger,
                    target,
                    coordinate,
                    cacheRegion,
                    config
            ));
        }
        triggers.keySet().removeIf(id -> !next.containsKey(id));
        for (Map.Entry<UUID, TriggerConfiguration> entry : next.entrySet()) {
            TriggerRuntime existing = triggers.get(entry.getKey());
            TriggerConfiguration replacement = entry.getValue();
            if (existing == null
                    || !existing.configuration.trigger.equals(
                    replacement.trigger
            )) {
                triggers.put(
                        entry.getKey(),
                        new TriggerRuntime(replacement, ticks)
                );
            } else {
                existing.configuration = replacement;
            }
        }
    }

    void tick() {
        ticks++;
        for (TriggerRuntime trigger : triggers.values()) {
            synchronize(trigger);
        }
        for (TriggerRuntime trigger : triggers.values()) {
            if (trigger.configuration.trigger.type()
                    == TaskTriggerType.CACHE_UPDATED) {
                dispatchCacheEvent(trigger);
                continue;
            }
            if ((trigger.state != TaskTriggerState.IDLE
                    && trigger.state != TaskTriggerState.ERROR)
                    || ticks < trigger.nextCheckTick) {
                continue;
            }
            evaluate(trigger);
        }
    }

    void cacheUpdated(UUID regionId) {
        for (TriggerRuntime runtime : triggers.values()) {
            TaskTriggerDefinition trigger = runtime.configuration.trigger;
            if (trigger.type() != TaskTriggerType.CACHE_UPDATED
                    || !regionId.equals(trigger.cacheRegionId())) {
                continue;
            }
            long readyAt = ticks + trigger.intervalTicks();
            if (!runtime.cacheEventPending
                    || readyAt > runtime.cacheEventReadyAtTick) {
                runtime.cacheEventReadyAtTick = readyAt;
            }
            runtime.cacheEventPending = true;
            if (!runtime.waitingForRun
                    && runtime.state != TaskTriggerState.COOLDOWN) {
                runtime.state = TaskTriggerState.IDLE;
                runtime.message = "缓存区域“"
                        + runtime.configuration.cacheRegion.name()
                        + "”有新物品，等待防抖后创建分类任务";
            }
        }
    }

    List<TaskTriggerSnapshot> snapshots() {
        List<TaskTriggerSnapshot> result = new ArrayList<>();
        for (TriggerRuntime runtime : triggers.values()) {
            TriggerConfiguration config = runtime.configuration;
            result.add(new TaskTriggerSnapshot(
                    config.trigger.id(),
                    config.trigger.name(),
                    config.trigger.type(),
                    config.target.id(),
                    config.target.name(),
                    config.trigger.priority(),
                    runtime.state,
                    runtime.message
            ));
        }
        result.sort(Comparator.comparing(TaskTriggerSnapshot::triggerName));
        return List.copyOf(result);
    }

    private void synchronize(TriggerRuntime runtime) {
        if (runtime.configuration.trigger.type()
                == TaskTriggerType.CACHE_UPDATED) {
            synchronizeCacheEvent(runtime);
            return;
        }
        if (!runtime.waitingForRun) {
            if (runtime.state == TaskTriggerState.COOLDOWN
                    && ticks >= runtime.cooldownUntilTick) {
                runtime.state = TaskTriggerState.IDLE;
                runtime.nextCheckTick = ticks;
                runtime.message = idleMessage(runtime.configuration.trigger);
            }
            return;
        }
        if (host.taskPending(runtime.configuration.target.id())) {
            runtime.state = TaskTriggerState.RUNNING;
            runtime.message = "目标任务正在排队或执行";
            return;
        }
        runtime.waitingForRun = false;
        startCooldown(runtime);
    }

    private void synchronizeCacheEvent(TriggerRuntime runtime) {
        if (runtime.waitingForRun) {
            if (host.taskPending(runtime.configuration.target.id())) {
                runtime.state = TaskTriggerState.RUNNING;
                runtime.message = "分类任务正在排队或执行";
                return;
            }
            runtime.waitingForRun = false;
            startCooldown(runtime);
            return;
        }
        if (runtime.state == TaskTriggerState.COOLDOWN
                && ticks >= runtime.cooldownUntilTick) {
            runtime.state = TaskTriggerState.IDLE;
            runtime.message = runtime.cacheEventPending
                    ? "缓存已更新，等待防抖后创建分类任务"
                    : idleMessage(runtime.configuration.trigger);
        } else if (runtime.state != TaskTriggerState.COOLDOWN
                && runtime.cacheEventPending) {
            runtime.message = "缓存已更新，等待防抖后创建分类任务";
        }
    }

    private void dispatchCacheEvent(TriggerRuntime runtime) {
        if (!runtime.cacheEventPending
                || runtime.waitingForRun
                || ticks < runtime.cacheEventReadyAtTick) {
            return;
        }
        if (runtime.state == TaskTriggerState.COOLDOWN
                && ticks < runtime.cooldownUntilTick) {
            return;
        }
        TaskTriggerDefinition trigger = runtime.configuration.trigger;
        try {
            if (host.taskPending(runtime.configuration.target.id())) {
                runtime.waitingForRun = true;
                runtime.state = TaskTriggerState.RUNNING;
                runtime.message = "缓存有新变化，等待当前分类任务完成";
                return;
            }
            host.start(
                    runtime.configuration.target,
                    trigger.priority(),
                    runtime.configuration.config
            );
            runtime.cacheEventPending = false;
            runtime.waitingForRun = true;
            runtime.state = TaskTriggerState.RUNNING;
            runtime.message = "缓存有新物品，已加入"
                    + priorityLabel(trigger.priority())
                    + "分类任务";
        } catch (RuntimeException exception) {
            runtime.state = TaskTriggerState.ERROR;
            runtime.message = safeMessage(exception);
        }
    }

    private void evaluate(TriggerRuntime runtime) {
        TaskTriggerDefinition trigger = runtime.configuration.trigger;
        runtime.nextCheckTick = ticks + trigger.intervalTicks();
        try {
            ConditionResult condition = switch (trigger.type()) {
                case DROPPED_ITEMS -> droppedItemCondition(runtime);
                case FOOD_LEVEL_AT_OR_BELOW -> foodLevelCondition(runtime);
                case CACHE_UPDATED -> ConditionResult.miss();
            };
            if (condition.error() != null) {
                runtime.state = TaskTriggerState.ERROR;
                runtime.message = condition.error();
                return;
            }
            if (!condition.matched()) {
                runtime.state = TaskTriggerState.IDLE;
                runtime.message = idleMessage(trigger);
                return;
            }
            host.start(
                    runtime.configuration.target,
                    trigger.priority(),
                    runtime.configuration.config
            );
            runtime.waitingForRun = true;
            runtime.state = TaskTriggerState.RUNNING;
            runtime.message = "条件命中，已加入"
                    + priorityLabel(trigger.priority())
                    + "任务“"
                    + runtime.configuration.target.name()
                    + "”";
        } catch (RuntimeException exception) {
            runtime.state = TaskTriggerState.ERROR;
            runtime.message = safeMessage(exception);
        }
    }

    private ConditionResult droppedItemCondition(TriggerRuntime runtime) {
        TaskTriggerDefinition trigger = runtime.configuration.trigger;
        CoordinatePoint coordinate = runtime.configuration.coordinate;
        if (coordinate == null) {
            return ConditionResult.error("触发坐标不存在");
        }
        if (coordinate.dimension() != null
                && !navigation.isInDimension(coordinate.dimension())) {
            return ConditionResult.error("等待进入坐标所在维度");
        }
        BlockPosition center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        int radius = coordinate.radius() == null
                ? DEFAULT_RADIUS
                : coordinate.radius();
        List<DroppedItem> matches = droppedItems.observe(center, radius)
                .stream()
                .filter(item -> trigger.matches(item.itemId()))
                .toList();
        return matches.isEmpty()
                ? ConditionResult.miss()
                : ConditionResult.hit();
    }

    private ConditionResult foodLevelCondition(TriggerRuntime runtime) {
        TaskTriggerDefinition trigger = runtime.configuration.trigger;
        FoodStatus food = foodConsumption.observe();
        if (!food.connected()) {
            return ConditionResult.error("无法读取当前饱食度");
        }
        return food.foodLevel() <= trigger.foodLevelThreshold()
                ? ConditionResult.hit()
                : ConditionResult.miss();
    }

    private void startCooldown(TriggerRuntime runtime) {
        int cooldown = runtime.configuration.trigger.cooldownTicks();
        runtime.cooldownUntilTick = ticks + cooldown;
        runtime.nextCheckTick = runtime.cooldownUntilTick;
        runtime.state = cooldown == 0
                ? TaskTriggerState.IDLE
                : TaskTriggerState.COOLDOWN;
        runtime.message = cooldown == 0
                ? idleMessage(runtime.configuration.trigger)
                : "任务结束后冷却 "
                        + String.format("%.1f", cooldown / 20.0)
                        + " 秒";
    }

    private static String idleMessage(TaskTriggerDefinition trigger) {
        if (trigger.type() == TaskTriggerType.CACHE_UPDATED) {
            return "空闲 · 等待缓存区域收到物品";
        }
        if (trigger.type() == TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW) {
            return "空闲 · 等待饱食度不高于 "
                    + trigger.foodLevelThreshold();
        }
        String filter = trigger.itemFilter() == null
                ? "全部物品"
                : trigger.itemFilter();
        return "空闲 · 等待掉落物：" + filter;
    }

    private static String priorityLabel(TaskPriority priority) {
        return switch (priority) {
            case BACKGROUND -> "后台";
            case NORMAL -> "普通";
            case URGENT -> "紧急";
        };
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record ConditionResult(boolean matched, String error) {
        private static ConditionResult hit() {
            return new ConditionResult(true, null);
        }

        private static ConditionResult miss() {
            return new ConditionResult(false, null);
        }

        private static ConditionResult error(String message) {
            return new ConditionResult(false, message);
        }
    }

    private static final class TriggerConfiguration {
        private final TaskTriggerDefinition trigger;
        private final TaskDefinition target;
        private final CoordinatePoint coordinate;
        private final RegionDefinition cacheRegion;
        private final AppConfig config;

        private TriggerConfiguration(
                TaskTriggerDefinition trigger,
                TaskDefinition target,
                CoordinatePoint coordinate,
                RegionDefinition cacheRegion,
                AppConfig config
        ) {
            this.trigger = trigger;
            this.target = target;
            this.coordinate = coordinate;
            this.cacheRegion = cacheRegion;
            this.config = config;
        }
    }

    private static final class TriggerRuntime {
        private TriggerConfiguration configuration;
        private TaskTriggerState state = TaskTriggerState.IDLE;
        private String message;
        private boolean waitingForRun;
        private boolean cacheEventPending;
        private long nextCheckTick;
        private long cooldownUntilTick;
        private long cacheEventReadyAtTick;

        private TriggerRuntime(
                TriggerConfiguration configuration,
                long currentTick
        ) {
            this.configuration = configuration;
            this.nextCheckTick = currentTick;
            this.message = idleMessage(configuration.trigger);
        }
    }
}
