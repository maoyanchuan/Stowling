package dev.stowling.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTaskRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsRecoveryMetadataAcrossRestart() {
        Path path = tempDir.resolve("tasks.db");
        UUID taskId = UUID.randomUUID();
        String failure = "server rejected ' OR 1=1 --";
        TaskSnapshot original = PersistenceFixtures.task(
                taskId,
                TaskStatus.WAITING,
                AutomationState.IDLE_WAITING,
                Optional.of(failure)
        );
        PersistedTask persisted = new PersistedTask(
                original,
                Optional.of("pickup-complete"),
                2
        );

        new SqliteTaskRepository(new StowlingDatabase(path)).save(persisted);
        SqliteTaskRepository reopened = new SqliteTaskRepository(
                new StowlingDatabase(path)
        );

        PersistedTask loaded = reopened.findRecordById(taskId).orElseThrow();
        assertEquals(original, loaded.snapshot());
        assertEquals(Optional.of("pickup-complete"), loaded.recoveryCheckpoint());
        assertEquals(2, loaded.retryCount());
    }

    @Test
    void recoveryPlansContainOnlyResumableHighLevelStates() {
        SqliteTaskRepository repository = new SqliteTaskRepository(
                new StowlingDatabase(tempDir.resolve("recovery.db"))
        );
        UUID runningId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        repository.save(PersistenceFixtures.task(
                runningId,
                TaskStatus.RUNNING,
                AutomationState.SORTING,
                Optional.empty()
        ));
        repository.save(PersistenceFixtures.task(
                failedId,
                TaskStatus.FAILED,
                AutomationState.FAILED,
                Optional.of("station blocked")
        ));

        List<TaskRecoveryPlan> plans = repository.findRecoveryPlans();

        assertEquals(1, plans.size());
        assertEquals(runningId, plans.getFirst().taskId());
        assertEquals(AutomationState.SORTING, plans.getFirst().resumeState());
    }

    @Test
    void upsertsMutableTaskState() {
        SqliteTaskRepository repository = new SqliteTaskRepository(
                new StowlingDatabase(tempDir.resolve("upsert.db"))
        );
        UUID taskId = UUID.randomUUID();
        TaskSnapshot waiting = PersistenceFixtures.task(
                taskId,
                TaskStatus.WAITING,
                AutomationState.IDLE_WAITING,
                Optional.empty()
        );
        TaskSnapshot completed = PersistenceFixtures.task(
                taskId,
                TaskStatus.SUCCEEDED,
                AutomationState.COMPLETED,
                Optional.empty()
        );

        repository.save(waiting);
        repository.save(completed);

        assertEquals(completed, repository.findById(taskId).orElseThrow());
        assertTrue(repository.findRecoveryPlans().isEmpty());
    }

    @Test
    void taskSchemaDoesNotPersistSlotOrClickInstructions() throws Exception {
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve("schema.db"));
        database.ensureMigrated();
        Set<String> columns = new HashSet<>();
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(tasks)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }

        assertFalse(columns.stream().anyMatch(column -> column.contains("slot")));
        assertFalse(columns.stream().anyMatch(column -> column.contains("click")));
    }
}
