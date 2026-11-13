package dev.stowling.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationCheckpoint;
import dev.stowling.automation.PickupStage;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAutomationCheckpointStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsVersionedJsonCheckpoint() {
        SqliteTaskRepository tasks = repository("round-trip.db");
        UUID taskId = saveTask(tasks);
        SqliteAutomationCheckpointStore store = new SqliteAutomationCheckpointStore(tasks);
        AutomationCheckpoint checkpoint = new AutomationCheckpoint(
                taskId,
                PickupStage.DELIVER_LOOSE,
                Optional.of("quarry"),
                Set.of("drop-1:minecraft:stone:1", "drop-2:minecraft:diamond:2"),
                Optional.of(Instant.parse("2026-11-13T12:05:00Z")),
                Map.of("minecraft:stone", 4),
                Optional.of("cache-west"),
                2
        );

        store.save(checkpoint);

        String persisted = tasks.findRecordById(taskId)
                .orElseThrow()
                .recoveryCheckpoint()
                .orElseThrow();
        assertTrue(persisted.startsWith("{\"version\":1,"), persisted);
        assertEquals(Optional.of(checkpoint), store.load(taskId));
    }

    @Test
    void loadsLegacySiteStageCheckpoint() {
        SqliteTaskRepository tasks = repository("legacy.db");
        UUID taskId = saveTask(tasks);
        PersistedTask current = tasks.findRecordById(taskId).orElseThrow();
        tasks.save(new PersistedTask(
                current.snapshot(),
                Optional.of("quarry|CLASSIFY_BUFFER"),
                0
        ));
        SqliteAutomationCheckpointStore store = new SqliteAutomationCheckpointStore(tasks);

        AutomationCheckpoint loaded = store.load(taskId).orElseThrow();

        assertEquals(PickupStage.CLASSIFY_BUFFER, loaded.stage());
        assertEquals(Optional.of("quarry"), loaded.planReference());
        assertTrue(loaded.observedInputKeys().isEmpty());
        assertTrue(loaded.selectedCacheId().isEmpty());
        assertEquals(0, loaded.classificationCacheIndex());
    }

    private SqliteTaskRepository repository(String fileName) {
        return new SqliteTaskRepository(new StowlingDatabase(tempDir.resolve(fileName)));
    }

    private static UUID saveTask(SqliteTaskRepository repository) {
        UUID taskId = UUID.randomUUID();
        repository.save(PersistenceFixtures.task(
                taskId,
                TaskStatus.RUNNING,
                AutomationState.SORTING,
                Optional.empty()
        ));
        return taskId;
    }
}
