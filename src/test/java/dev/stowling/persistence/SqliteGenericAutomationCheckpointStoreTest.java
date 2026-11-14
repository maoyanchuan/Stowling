package dev.stowling.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationExecutionStatus;
import dev.stowling.automation.GenericAutomationCheckpoint;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteGenericAutomationCheckpointStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsDurableAutomationProgress() {
        SqliteTaskRepository tasks = repository("round-trip.db");
        UUID taskId = saveAutomationTask(tasks);
        SqliteGenericAutomationCheckpointStore store =
                new SqliteGenericAutomationCheckpointStore(tasks);
        GenericAutomationCheckpoint checkpoint = new GenericAutomationCheckpoint(
                taskId,
                "daily",
                2,
                Map.of("phase", "POLLING", "operationKey", "op-2"),
                Optional.of("deposit-confirmed"),
                AutomationExecutionStatus.RUNNING
        );

        store.save(checkpoint);

        assertEquals(Optional.of(checkpoint), store.load(taskId));
        PersistedTask persisted = tasks.findRecordById(taskId).orElseThrow();
        assertEquals(TaskStatus.RUNNING, persisted.snapshot().status());
        assertTrue(persisted.recoveryCheckpoint().orElseThrow().contains("\"version\":1"));
    }

    @Test
    void isolatesPickupAndAutomationCheckpointsByTaskKind() {
        SqliteTaskRepository tasks = repository("isolation.db");
        UUID pickupId = UUID.randomUUID();
        tasks.save(new TaskSnapshot(
                pickupId,
                TaskKind.PICKUP,
                TaskStatus.RUNNING,
                AutomationState.PICKING_UP,
                Instant.now(),
                Instant.now(),
                Optional.empty()
        ));
        SqliteGenericAutomationCheckpointStore store =
                new SqliteGenericAutomationCheckpointStore(tasks);
        GenericAutomationCheckpoint checkpoint = new GenericAutomationCheckpoint(
                pickupId,
                "daily",
                0,
                Map.of(),
                Optional.empty(),
                AutomationExecutionStatus.RUNNING
        );

        assertTrue(store.load(pickupId).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.save(checkpoint));
    }

    @Test
    void deleteRemovesOnlyTheRecoveryCheckpoint() {
        SqliteTaskRepository tasks = repository("delete.db");
        UUID taskId = saveAutomationTask(tasks);
        SqliteGenericAutomationCheckpointStore store =
                new SqliteGenericAutomationCheckpointStore(tasks);
        store.save(new GenericAutomationCheckpoint(
                taskId,
                "daily",
                0,
                Map.of(),
                Optional.empty(),
                AutomationExecutionStatus.PAUSED
        ));

        store.delete(taskId);

        assertFalse(tasks.findRecordById(taskId).orElseThrow()
                .recoveryCheckpoint()
                .isPresent());
        assertTrue(tasks.findById(taskId).isPresent());
    }

    private SqliteTaskRepository repository(String fileName) {
        return new SqliteTaskRepository(new StowlingDatabase(tempDir.resolve(fileName)));
    }

    private static UUID saveAutomationTask(SqliteTaskRepository tasks) {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        tasks.save(new TaskSnapshot(
                taskId,
                TaskKind.AUTOMATION,
                TaskStatus.PENDING,
                AutomationState.IDLE_WAITING,
                now,
                now,
                Optional.empty()
        ));
        return taskId;
    }
}
