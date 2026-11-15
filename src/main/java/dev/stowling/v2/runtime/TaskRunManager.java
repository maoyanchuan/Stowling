package dev.stowling.v2.runtime;

import dev.stowling.automation.NavigationPort;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TaskRunManager {
    private static final int HISTORY_LIMIT = 50;

    private final NavigationPort navigation;
    private final TaskModuleRuntimeRegistry registry;
    private final Deque<TaskRunSnapshot> history = new ArrayDeque<>();
    private ActiveRun active;

    public TaskRunManager(NavigationPort navigation) {
        this(navigation, TaskModuleRuntimeRegistry.unavailablePorts(navigation));
    }

    public TaskRunManager(
            NavigationPort navigation,
            TaskModuleRuntimeRegistry registry
    ) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates
    ) {
        return start(task, coordinates, List.of());
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions
    ) {
        return start(task, coordinates, regions, List.of());
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones
    ) {
        Objects.requireNonNull(task, "task");
        if (busy()) {
            throw new TaskRunBusyException();
        }
        Map<UUID, CoordinatePoint> frozenCoordinates = indexCoordinates(
                coordinates
        );
        Map<UUID, RegionDefinition> frozenRegions = indexRegions(regions);
        Map<UUID, CategoryZoneDefinition> frozenCategoryZones =
                indexCategoryZones(categoryZones);
        for (TaskStep step : task.steps()) {
            ConfigValidator.requireValidStep(
                    step,
                    frozenCoordinates,
                    frozenRegions,
                    frozenCategoryZones
            );
        }
        Instant now = Instant.now();
        TaskRunSnapshot snapshot = new TaskRunSnapshot(
                UUID.randomUUID(),
                task.id(),
                task.name(),
                TaskRunState.RUNNING,
                0,
                task.steps().size(),
                "正在运行",
                now,
                now
        );
        active = new ActiveRun(
                snapshot,
                List.copyOf(task.steps()),
                Map.copyOf(frozenCoordinates),
                Map.copyOf(frozenRegions),
                Map.copyOf(frozenCategoryZones),
                new TaskRunWorkspace()
        );
        retain(snapshot);
        return snapshot;
    }

    public synchronized void tick() {
        if (!isActive()) {
            return;
        }
        if (active.cancelRequested) {
            finishCancellation();
            return;
        }
        if (active.snapshot.completedSteps() >= active.steps.size()) {
            finish(TaskRunState.SUCCEEDED, "任务已完成");
            return;
        }
        TaskModuleResult result;
        try {
            TaskModuleExecutor executor = executor();
            result = active.executorStarted
                    ? executor.tick()
                    : startExecutor(executor);
        } catch (RuntimeException exception) {
            requestCancel();
            finish(
                    TaskRunState.FAILED,
                    "模块执行失败: " + safeMessage(exception)
            );
            return;
        }
        apply(result);
    }

    public synchronized void cancel(UUID runId) {
        if (active == null || !active.snapshot.runId().equals(runId)) {
            throw new TaskRunNotFoundException();
        }
        if (!isActive()) {
            throw new TaskRunNotFoundException();
        }
        active.cancelRequested = true;
        requestCancel();
        update(active.snapshot.state(), "正在取消任务");
    }

    public synchronized Optional<TaskRunSnapshot> status() {
        return Optional.ofNullable(active).map(run -> run.snapshot);
    }

    public synchronized List<TaskRunSnapshot> runs() {
        return List.copyOf(history);
    }

    public synchronized boolean busy() {
        return isActive();
    }

    public synchronized void cancelAll() {
        if (!isActive()) {
            return;
        }
        active.cancelRequested = true;
        requestCancel();
        finish(TaskRunState.CANCELLED, "任务已取消");
    }

    private TaskModuleResult startExecutor(TaskModuleExecutor executor) {
        active.executorStarted = true;
        return executor.start();
    }

    private TaskModuleExecutor executor() {
        if (active.executor == null) {
            TaskStep step = active.steps.get(active.snapshot.completedSteps());
            TaskExecutionContext context = new TaskExecutionContext(
                    navigation,
                    registry.droppedItems(),
                    registry.containerRegions(),
                    registry.containerCategories(),
                    registry.containerTransfers(),
                    registry.containerWithdrawals(),
                    registry.categoryZones(),
                    active.coordinates,
                    active.regions,
                    active.categoryZones,
                    active.workspace
            );
            active.executor = registry.create(step, context);
            active.executorStarted = false;
        }
        return active.executor;
    }

    private void apply(TaskModuleResult result) {
        switch (result.state()) {
            case RUNNING -> update(TaskRunState.RUNNING, result.message());
            case WAITING -> update(TaskRunState.WAITING, result.message());
            case SUCCEEDED -> completeCurrentStep(result.message());
            case FAILED -> {
                requestCancel();
                finish(TaskRunState.FAILED, result.message());
            }
        }
    }

    private void completeCurrentStep(String message) {
        active.executor = null;
        active.executorStarted = false;
        int completed = active.snapshot.completedSteps() + 1;
        update(TaskRunState.RUNNING, completed, message);
        if (completed >= active.steps.size()) {
            finish(TaskRunState.SUCCEEDED, "任务已完成");
        }
    }

    private void requestCancel() {
        if (active.executor != null && !active.executorCancelled) {
            active.executor.cancel();
            active.executorCancelled = true;
        }
    }

    private void finishCancellation() {
        requestCancel();
        StopOutcome outcome = navigation.requestStop();
        if (outcome == StopOutcome.PENDING) {
            update(TaskRunState.WAITING, "正在停止导航");
            return;
        }
        String message = outcome == StopOutcome.CLEAN
                ? "任务已取消"
                : "任务已取消，但导航停止状态未知";
        finish(TaskRunState.CANCELLED, message);
    }

    private void update(TaskRunState state, String message) {
        update(state, active.snapshot.completedSteps(), message);
    }

    private void update(TaskRunState state, int completedSteps, String message) {
        TaskRunSnapshot old = active.snapshot;
        active.snapshot = new TaskRunSnapshot(
                old.runId(),
                old.taskId(),
                old.taskName(),
                state,
                completedSteps,
                old.totalSteps(),
                message,
                old.startedAt(),
                Instant.now()
        );
        replaceRetained(active.snapshot);
    }

    private void finish(TaskRunState state, String message) {
        TaskRunSnapshot old = active.snapshot;
        TaskRunSnapshot finished = new TaskRunSnapshot(
                old.runId(),
                old.taskId(),
                old.taskName(),
                state,
                state == TaskRunState.SUCCEEDED
                        ? old.totalSteps()
                        : old.completedSteps(),
                old.totalSteps(),
                message,
                old.startedAt(),
                Instant.now()
        );
        replaceRetained(finished);
        active = null;
    }

    private void retain(TaskRunSnapshot snapshot) {
        history.addFirst(snapshot);
        trimHistory();
    }

    private void replaceRetained(TaskRunSnapshot snapshot) {
        List<TaskRunSnapshot> values = new ArrayList<>(history);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).runId().equals(snapshot.runId())) {
                values.set(index, snapshot);
                history.clear();
                history.addAll(values);
                return;
            }
        }
        retain(snapshot);
    }

    private void trimHistory() {
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    private boolean isActive() {
        return active != null
                && (active.snapshot.state() == TaskRunState.RUNNING
                || active.snapshot.state() == TaskRunState.WAITING);
    }

    private static Map<UUID, CoordinatePoint> indexCoordinates(
            List<CoordinatePoint> coordinates
    ) {
        Map<UUID, CoordinatePoint> result = new LinkedHashMap<>();
        for (CoordinatePoint point : coordinates) {
            result.put(point.id(), point);
        }
        return result;
    }

    private static Map<UUID, RegionDefinition> indexRegions(
            List<RegionDefinition> regions
    ) {
        Map<UUID, RegionDefinition> result = new LinkedHashMap<>();
        for (RegionDefinition region : regions) {
            result.put(region.id(), region);
        }
        return result;
    }

    private static Map<UUID, CategoryZoneDefinition> indexCategoryZones(
            List<CategoryZoneDefinition> categoryZones
    ) {
        Map<UUID, CategoryZoneDefinition> result = new LinkedHashMap<>();
        for (CategoryZoneDefinition zone : categoryZones) {
            result.put(zone.id(), zone);
        }
        return result;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static final class ActiveRun {
        private TaskRunSnapshot snapshot;
        private final List<TaskStep> steps;
        private final Map<UUID, CoordinatePoint> coordinates;
        private final Map<UUID, RegionDefinition> regions;
        private final Map<UUID, CategoryZoneDefinition> categoryZones;
        private final TaskRunWorkspace workspace;
        private TaskModuleExecutor executor;
        private boolean executorStarted;
        private boolean executorCancelled;
        private boolean cancelRequested;

        private ActiveRun(
                TaskRunSnapshot snapshot,
                List<TaskStep> steps,
                Map<UUID, CoordinatePoint> coordinates,
                Map<UUID, RegionDefinition> regions,
                Map<UUID, CategoryZoneDefinition> categoryZones,
                TaskRunWorkspace workspace
        ) {
            this.snapshot = snapshot;
            this.steps = steps;
            this.coordinates = coordinates;
            this.regions = regions;
            this.categoryZones = categoryZones;
            this.workspace = workspace;
        }
    }
}
