package dev.stowling.v2.runtime;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
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
    private final Deque<TaskRunSnapshot> history = new ArrayDeque<>();
    private ActiveRun active;

    public TaskRunManager(NavigationPort navigation) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates
    ) {
        if (active != null && active.snapshot.state() == TaskRunState.RUNNING) {
            throw new TaskRunBusyException();
        }
        Map<UUID, CoordinatePoint> targets = new LinkedHashMap<>();
        for (CoordinatePoint point : coordinates) {
            targets.put(point.id(), point);
        }
        for (TaskStep step : task.steps()) {
            ConfigValidator.requireValidStep(step, targets);
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
                Map.copyOf(targets)
        );
        retain(snapshot);
        return snapshot;
    }

    public synchronized void tick() {
        if (active == null
                || active.snapshot.state() != TaskRunState.RUNNING) {
            return;
        }
        if (active.cancelRequested) {
            StopOutcome outcome = navigation.requestStop();
            if (outcome != StopOutcome.PENDING) {
                finish(TaskRunState.CANCELLED, "任务已取消");
            } else {
                update("正在取消任务");
            }
            return;
        }
        if (active.snapshot.completedSteps() >= active.steps.size()) {
            finish(TaskRunState.SUCCEEDED, "任务已完成");
            return;
        }
        TaskStep step = active.steps.get(active.snapshot.completedSteps());
        UUID coordinateId = UUID.fromString(
                step.parameters().get(ConfigValidator.COORDINATE_ID)
        );
        CoordinatePoint target = active.targets.get(coordinateId);
        BlockPosition position = new BlockPosition(target.x(), target.y(), target.z());
        if (target.dimension() != null
                && !navigation.isInDimension(target.dimension())) {
            finish(
                    TaskRunState.FAILED,
                    "无法前往 " + target.name() + "：维度不匹配"
            );
            return;
        }
        NavigationStatus status;
        if (!active.navigationStarted) {
            active.navigationStarted = true;
            status = navigation.start(position);
        } else {
            status = navigation.status(position, target.radius());
        }
        switch (status) {
            case ARRIVED -> {
                active.navigationStarted = false;
                int completed = active.snapshot.completedSteps() + 1;
                update(completed, "已到达 " + target.name());
                if (completed >= active.steps.size()) {
                    finish(TaskRunState.SUCCEEDED, "任务已完成");
                }
            }
            case NAVIGATING -> update("正在前往 " + target.name());
            case FAILED -> finish(TaskRunState.FAILED, "无法前往 " + target.name());
        }
    }

    public synchronized void cancel(UUID runId) {
        if (active == null || !active.snapshot.runId().equals(runId)) {
            throw new TaskRunNotFoundException();
        }
        active.cancelRequested = true;
        update("正在取消任务");
    }

    public synchronized Optional<TaskRunSnapshot> status() {
        return Optional.ofNullable(active).map(run -> run.snapshot);
    }

    public synchronized List<TaskRunSnapshot> runs() {
        return List.copyOf(history);
    }

    public synchronized boolean busy() {
        return active != null && active.snapshot.state() == TaskRunState.RUNNING;
    }

    public synchronized void cancelAll() {
        if (active != null && active.snapshot.state() == TaskRunState.RUNNING) {
            active.cancelRequested = true;
            navigation.requestStop();
            finish(TaskRunState.CANCELLED, "任务已取消");
        }
    }

    private void update(String message) {
        update(active.snapshot.completedSteps(), message);
    }

    private void update(int completedSteps, String message) {
        TaskRunSnapshot old = active.snapshot;
        active.snapshot = new TaskRunSnapshot(
                old.runId(),
                old.taskId(),
                old.taskName(),
                old.state(),
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
        active.snapshot = finished;
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

    private static final class ActiveRun {
        private TaskRunSnapshot snapshot;
        private final List<TaskStep> steps;
        private final Map<UUID, CoordinatePoint> targets;
        private boolean navigationStarted;
        private boolean cancelRequested;

        private ActiveRun(
                TaskRunSnapshot snapshot,
                List<TaskStep> steps,
                Map<UUID, CoordinatePoint> targets
        ) {
            this.snapshot = snapshot;
            this.steps = steps;
            this.targets = targets;
        }
    }
}
