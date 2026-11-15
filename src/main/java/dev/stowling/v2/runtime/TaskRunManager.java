package dev.stowling.v2.runtime;

import dev.stowling.automation.NavigationPort;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TaskRunManager {
    private static final int HISTORY_LIMIT = 50;
    private static final int BACKGROUND_REPEAT_DELAY_TICKS = 100;

    private final NavigationPort navigation;
    private final TaskModuleRuntimeRegistry registry;
    private final TaskTriggerManager triggers;
    private final Deque<TaskRunSnapshot> history = new ArrayDeque<>();
    private final List<QueuedRun> queue = new ArrayList<>();
    private ActiveRun active;
    private long nextSequence;
    private long ticks;

    public TaskRunManager(NavigationPort navigation) {
        this(navigation, TaskModuleRuntimeRegistry.unavailablePorts(navigation));
    }

    public TaskRunManager(
            NavigationPort navigation,
            TaskModuleRuntimeRegistry registry
    ) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.triggers = new TaskTriggerManager(
                registry.droppedItems(),
                registry.foodConsumption(),
                navigation,
                new TaskTriggerManager.Host() {
                    @Override
                    public TaskRunSnapshot start(
                            TaskDefinition task,
                            TaskPriority priority,
                            AppConfig config
                    ) {
                        return TaskRunManager.this.start(
                                task,
                                config.coordinates(),
                                config.regions(),
                                config.categoryZones(),
                                priority,
                                false
                        );
                    }

                    @Override
                    public boolean taskPending(UUID taskId) {
                        return isTaskPending(taskId);
                    }
                }
        );
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
        return start(task, coordinates, regions, categoryZones, null);
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            TaskPriority priorityOverride
    ) {
        return start(
                task,
                coordinates,
                regions,
                categoryZones,
                priorityOverride,
                true
        );
    }

    private TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            TaskPriority priorityOverride,
            boolean allowRepeat
    ) {
        PreparedRun prepared = prepare(
                task,
                coordinates,
                regions,
                categoryZones,
                priorityOverride,
                allowRepeat
        );
        if (active != null && active.prepared.task.id().equals(task.id())) {
            return active.snapshot;
        }
        Optional<QueuedRun> existing = queue.stream()
                .filter(run -> run.prepared.task.id().equals(task.id()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().snapshot;
        }
        QueuedRun queued = new QueuedRun(
                UUID.randomUUID(),
                prepared,
                Instant.now(),
                nextSequence++,
                ticks
        );
        queue.add(queued);
        retain(queued.snapshot);
        if (active == null) {
            TaskRunSnapshot started = startNextQueued();
            return started != null
                    && started.runId().equals(queued.snapshot.runId())
                    ? started
                    : queued.snapshot;
        }
        if (prepared.priority.preempts(active.prepared.priority)) {
            requestPreemption(queued);
        }
        return queued.snapshot;
    }

    public synchronized void tick() {
        ticks++;
        triggers.tick();
        if (active != null) {
            if (active.cancelRequested) {
                finishCancellation();
                startNextQueued();
                return;
            }
            if (active.snapshot.completedSteps() >= active.prepared.steps.size()) {
                finish(TaskRunState.SUCCEEDED, "任务已完成");
                startNextQueued();
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
                startNextQueued();
                return;
            }
            apply(result);
        }
        startNextQueued();
    }

    public synchronized void cancel(UUID runId) {
        if (active != null && active.snapshot.runId().equals(runId)) {
            active.userCancelRequested = true;
            active.cancelRequested = true;
            requestCancel();
            update(active.snapshot.state(), "正在取消任务");
            return;
        }
        for (QueuedRun queued : List.copyOf(queue)) {
            if (!queued.snapshot.runId().equals(runId)) {
                continue;
            }
            queue.remove(queued);
            TaskRunSnapshot cancelled = snapshot(
                    queued,
                    TaskRunState.CANCELLED,
                    0,
                    "排队任务已取消",
                    Instant.now()
            );
            replaceRetained(cancelled);
            return;
        }
        throw new TaskRunNotFoundException();
    }

    public synchronized Optional<TaskRunSnapshot> status() {
        if (active != null) {
            return Optional.of(active.snapshot);
        }
        return queue.stream()
                .sorted(queueOrder())
                .findFirst()
                .map(run -> snapshot(
                        run,
                        TaskRunState.QUEUED,
                        0,
                        run.readyAtTick <= ticks
                                ? "排队等待"
                                : "等待后台任务冷却",
                        run.snapshot.updatedAt()
                ));
    }

    public synchronized List<TaskRunSnapshot> runs() {
        return List.copyOf(history);
    }

    public synchronized List<TaskRunSnapshot> queued() {
        return queue.stream()
                .sorted(queueOrder())
                .map(run -> snapshot(
                        run,
                        TaskRunState.QUEUED,
                        0,
                        run.readyAtTick <= ticks
                                ? "排队等待"
                                : "等待后台任务冷却",
                        run.snapshot.updatedAt()
                ))
                .toList();
    }

    public synchronized void refreshTriggers(AppConfig config) {
        triggers.refresh(config);
    }

    public synchronized List<TaskTriggerSnapshot> triggerStatuses() {
        return triggers.snapshots();
    }

    public synchronized boolean busy() {
        return isActive();
    }

    public synchronized void cancelAll() {
        for (QueuedRun queued : List.copyOf(queue)) {
            TaskRunSnapshot cancelled = snapshot(
                    queued,
                    TaskRunState.CANCELLED,
                    0,
                    "排队任务已取消",
                    Instant.now()
            );
            replaceRetained(cancelled);
        }
        queue.clear();
        if (active == null) {
            return;
        }
        active.userCancelRequested = true;
        requestCancel();
        finish(TaskRunState.CANCELLED, "任务已取消");
    }

    private PreparedRun prepare(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            TaskPriority priorityOverride,
            boolean allowRepeat
    ) {
        Objects.requireNonNull(task, "task");
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
        TaskPriority priority = priorityOverride == null
                ? task.priority()
                : priorityOverride;
        boolean repeating = allowRepeat
                && priority == TaskPriority.BACKGROUND
                && task.repeatWhenIdle();
        return new PreparedRun(
                task,
                List.copyOf(task.steps()),
                Map.copyOf(frozenCoordinates),
                Map.copyOf(frozenRegions),
                Map.copyOf(frozenCategoryZones),
                priority,
                repeating
        );
    }

    private TaskRunSnapshot startQueued(QueuedRun queued) {
        queue.remove(queued);
        TaskRunSnapshot running = snapshot(
                queued,
                TaskRunState.RUNNING,
                0,
                "正在运行",
                Instant.now()
        );
        active = new ActiveRun(running, queued.prepared);
        replaceRetained(running);
        return running;
    }

    private TaskRunSnapshot startNextQueued() {
        if (active != null) {
            return null;
        }
        QueuedRun next = queue.stream()
                .filter(run -> run.readyAtTick <= ticks)
                .min(queueOrder())
                .orElse(null);
        if (next != null) {
            return startQueued(next);
        }
        return null;
    }

    private void requestPreemption(QueuedRun queued) {
        if (active.userCancelRequested) {
            return;
        }
        active.preemptedByRunId = queued.snapshot.runId();
        active.cancelRequested = true;
        requestCancel();
        update(
                active.snapshot.state(),
                "正在停止任务，准备让位给“"
                        + queued.prepared.task.name()
                        + "”"
        );
    }

    private TaskModuleResult startExecutor(TaskModuleExecutor executor) {
        active.executorStarted = true;
        return executor.start();
    }

    private TaskModuleExecutor executor() {
        if (active.executor == null) {
            TaskStep step = active.prepared.steps.get(
                    active.snapshot.completedSteps()
            );
            TaskExecutionContext context = new TaskExecutionContext(
                    navigation,
                    registry.droppedItems(),
                    registry.containerRegions(),
                    registry.containerCategories(),
                    registry.containerTransfers(),
                    registry.containerWithdrawals(),
                    registry.containerInspections(),
                    registry.categoryMarkerCache(),
                    registry.foodConsumption(),
                    registry.categoryZones(),
                    active.prepared.coordinates,
                    active.prepared.regions,
                    active.prepared.categoryZones,
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
        if (completed >= active.prepared.steps.size()) {
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
        boolean preempted = active.preemptedByRunId != null
                && !active.userCancelRequested;
        PreparedRun interrupted = active.prepared;
        String interruptedBy = queue.stream()
                .filter(run -> run.snapshot.runId()
                        .equals(active.preemptedByRunId))
                .map(run -> run.prepared.task.name())
                .findFirst()
                .orElse("更高优先级任务");
        String message = preempted
                ? "被“" + interruptedBy + "”中断，将从开始处重新排队"
                : outcome == StopOutcome.CLEAN
                        ? "任务已取消"
                        : "任务已取消，但导航停止状态未知";
        finish(
                preempted ? TaskRunState.PREEMPTED : TaskRunState.CANCELLED,
                message
        );
        if (preempted) {
            enqueuePrepared(interrupted, 0, "被中断，等待从头重新运行");
        }
    }

    private void update(TaskRunState state, String message) {
        update(state, active.snapshot.completedSteps(), message);
    }

    private void update(
            TaskRunState state,
            int completedSteps,
            String message
    ) {
        TaskRunSnapshot old = active.snapshot;
        active.snapshot = new TaskRunSnapshot(
                old.runId(),
                old.taskId(),
                old.taskName(),
                old.priority(),
                old.repeating(),
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
                old.priority(),
                old.repeating(),
                state,
                state == TaskRunState.SUCCEEDED
                        ? old.totalSteps()
                        : old.completedSteps(),
                old.totalSteps(),
                message,
                old.startedAt(),
                Instant.now()
        );
        PreparedRun completed = active.prepared;
        replaceRetained(finished);
        active = null;
        if (completed.repeating
                && (state == TaskRunState.SUCCEEDED
                || state == TaskRunState.FAILED)) {
            enqueuePrepared(
                    completed,
                    BACKGROUND_REPEAT_DELAY_TICKS,
                    "等待后台任务冷却"
            );
        }
    }

    private void enqueuePrepared(
            PreparedRun prepared,
            int delayTicks,
            String message
    ) {
        if (active != null
                && active.prepared.task.id().equals(prepared.task.id())) {
            return;
        }
        if (queue.stream().anyMatch(
                run -> run.prepared.task.id().equals(prepared.task.id())
        )) {
            return;
        }
        Instant now = Instant.now();
        QueuedRun queued = new QueuedRun(
                UUID.randomUUID(),
                prepared,
                now,
                nextSequence++,
                ticks + delayTicks
        );
        queue.add(queued);
        retain(snapshot(
                queued,
                TaskRunState.QUEUED,
                0,
                message,
                now
        ));
    }

    private static Comparator<QueuedRun> queueOrder() {
        return Comparator
                .comparingInt((QueuedRun run) -> run.prepared.priority.rank())
                .reversed()
                .thenComparingLong(run -> run.sequence);
    }

    private static TaskRunSnapshot snapshot(
            QueuedRun queued,
            TaskRunState state,
            int completedSteps,
            String message,
            Instant updatedAt
    ) {
        return new TaskRunSnapshot(
                queued.snapshot.runId(),
                queued.prepared.task.id(),
                queued.prepared.task.name(),
                queued.prepared.priority,
                queued.prepared.repeating,
                state,
                completedSteps,
                queued.prepared.steps.size(),
                message,
                queued.snapshot.startedAt(),
                updatedAt
        );
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

    private boolean isTaskPending(UUID taskId) {
        if (active != null && active.prepared.task.id().equals(taskId)) {
            return true;
        }
        return queue.stream().anyMatch(
                run -> run.prepared.task.id().equals(taskId)
        );
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

    private record PreparedRun(
            TaskDefinition task,
            List<TaskStep> steps,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            TaskPriority priority,
            boolean repeating
    ) {
    }

    private static final class QueuedRun {
        private final PreparedRun prepared;
        private final long sequence;
        private final long readyAtTick;
        private final TaskRunSnapshot snapshot;

        private QueuedRun(
                UUID runId,
                PreparedRun prepared,
                Instant requestedAt,
                long sequence,
                long readyAtTick
        ) {
            this.prepared = prepared;
            this.sequence = sequence;
            this.readyAtTick = readyAtTick;
            this.snapshot = new TaskRunSnapshot(
                    runId,
                    prepared.task.id(),
                    prepared.task.name(),
                    prepared.priority,
                    prepared.repeating,
                    TaskRunState.QUEUED,
                    0,
                    prepared.steps.size(),
                    "排队等待",
                    requestedAt,
                    requestedAt
            );
        }
    }

    private static final class ActiveRun {
        private TaskRunSnapshot snapshot;
        private final PreparedRun prepared;
        private final TaskRunWorkspace workspace = new TaskRunWorkspace();
        private TaskModuleExecutor executor;
        private boolean executorStarted;
        private boolean executorCancelled;
        private boolean cancelRequested;
        private boolean userCancelRequested;
        private UUID preemptedByRunId;

        private ActiveRun(
                TaskRunSnapshot snapshot,
                PreparedRun prepared
        ) {
            this.snapshot = snapshot;
            this.prepared = prepared;
        }
    }
}
