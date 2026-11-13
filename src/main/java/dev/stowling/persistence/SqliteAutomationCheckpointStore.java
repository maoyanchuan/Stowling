package dev.stowling.persistence;

import dev.stowling.automation.AutomationCheckpoint;
import dev.stowling.automation.AutomationCheckpointStore;
import dev.stowling.automation.PickupStage;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists only the resumable pickup stage, never slot-level attempts or clicks.
 */
public final class SqliteAutomationCheckpointStore implements AutomationCheckpointStore {
    private final SqliteTaskRepository tasks;

    public SqliteAutomationCheckpointStore(SqliteTaskRepository tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public Optional<AutomationCheckpoint> load(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return tasks.findRecordById(taskId)
                .flatMap(PersistedTask::recoveryCheckpoint)
                .map(SqliteAutomationCheckpointStore::readCheckpoint)
                .map(parts -> new AutomationCheckpoint(
                        taskId,
                        PickupStage.valueOf(parts.stage()),
                        Optional.ofNullable(parts.siteId()),
                        Set.of(),
                        Optional.empty(),
                        Map.of()
                ));
    }

    @Override
    public void save(AutomationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        TaskSnapshot current = tasks.findById(checkpoint.taskId()).orElseThrow();
        TaskStatus status = statusOf(checkpoint.stage());
        AutomationState state = automationStateOf(checkpoint.stage());
        TaskSnapshot updated = new TaskSnapshot(
                current.id(),
                current.kind(),
                status,
                state,
                current.createdAt(),
                current.updatedAt().isAfter(java.time.Instant.now())
                        ? current.updatedAt()
                        : java.time.Instant.now(),
                current.failureReason()
        );
        tasks.save(new PersistedTask(
                updated,
                Optional.of(writeCheckpoint(checkpoint)),
                0
        ));
    }

    @Override
    public void delete(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
    }

    public static TaskStatus statusOf(PickupStage stage) {
        return switch (stage) {
            case NAVIGATE_PICKUP, COLLECT, DEPOSIT_BUFFER, CLASSIFY_BUFFER,
                    DELIVER_LOOSE, PACK -> TaskStatus.RUNNING;
            case WAIT_FOR_IDLE -> TaskStatus.WAITING;
            case COMPLETE -> TaskStatus.SUCCEEDED;
            case FAILED -> TaskStatus.FAILED;
            case PAUSED -> TaskStatus.PAUSED;
        };
    }

    public static AutomationState automationStateOf(PickupStage stage) {
        return switch (stage) {
            case NAVIGATE_PICKUP, COLLECT -> AutomationState.PICKING_UP;
            case DEPOSIT_BUFFER -> AutomationState.CACHING;
            case WAIT_FOR_IDLE -> AutomationState.IDLE_WAITING;
            case CLASSIFY_BUFFER, DELIVER_LOOSE -> AutomationState.SORTING;
            case PACK -> AutomationState.PACKING;
            case COMPLETE -> AutomationState.COMPLETED;
            case PAUSED, FAILED -> AutomationState.FAILED;
        };
    }

    private static String writeCheckpoint(AutomationCheckpoint checkpoint) {
        String siteId = checkpoint.planReference().orElse("");
        return siteId + "|" + checkpoint.stage().name();
    }

    private static CheckpointParts readCheckpoint(String value) {
        int separator = value.lastIndexOf('|');
        if (separator < 0) {
            return new CheckpointParts(null, value);
        }
        String siteId = value.substring(0, separator);
        String stage = value.substring(separator + 1);
        return new CheckpointParts(siteId.isEmpty() ? null : siteId, stage);
    }

    private record CheckpointParts(String siteId, String stage) {
    }
}
