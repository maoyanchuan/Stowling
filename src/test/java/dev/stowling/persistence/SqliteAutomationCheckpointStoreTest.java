package dev.stowling.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                Optional.of("north"),
                Optional.of("north"),
                4,
                Set.of("cache-east"),
                Optional.of(Instant.parse("2026-11-13T12:04:00Z")),
                Map.of("north", Instant.parse("2026-11-13T12:05:00Z")),
                Set.of("drop-1:minecraft:stone:1", "drop-2:minecraft:diamond:2"),
                Map.of("minecraft:stone", 4),
                Optional.of("cache-west"),
                2,
                true
        );

        store.save(checkpoint);

        String persisted = tasks.findRecordById(taskId)
                .orElseThrow()
                .recoveryCheckpoint()
                .orElseThrow();
        assertTrue(persisted.startsWith("{\"version\":2,"), persisted);
        assertEquals(
                Optional.of("quarry"),
                SqliteAutomationCheckpointStore.readPlanReference(taskId, persisted)
        );
        assertEquals(Optional.of(checkpoint), store.load(taskId));
    }

    @Test
    void loadsVersionOneJsonCheckpoint() {
        SqliteTaskRepository tasks = repository("version-one.db");
        UUID taskId = saveTask(tasks);
        PersistedTask current = tasks.findRecordById(taskId).orElseThrow();
        String checkpoint = "{"
                + "\"version\":1,"
                + "\"taskId\":\"" + taskId + "\","
                + "\"stage\":\"WAIT_FOR_IDLE\","
                + "\"planReference\":\"quarry\","
                + "\"observedInputKeys\":[],"
                + "\"idleSince\":\"2026-11-13T12:05:00Z\","
                + "\"inventoryBaseline\":{},"
                + "\"selectedCacheId\":\"cache-west\","
                + "\"classificationCacheIndex\":0"
                + "}";
        tasks.save(new PersistedTask(current.snapshot(), Optional.of(checkpoint), 0));

        AutomationCheckpoint loaded = new SqliteAutomationCheckpointStore(tasks)
                .load(taskId)
                .orElseThrow();

        assertEquals(Optional.of("quarry"), loaded.workflowId());
        assertEquals(PickupStage.WAIT_FOR_IDLE, loaded.stage());
        assertEquals(Optional.of(Instant.parse("2026-11-13T12:05:00Z")), loaded.idleSince());
    }

    @Test
    void safelyRejectsLegacyPausedCheckpoint() {
        assertLegacyTerminalCheckpointRejected("PAUSED");
    }

    @Test
    void safelyRejectsLegacyFailedCheckpoint() {
        assertLegacyTerminalCheckpointRejected("FAILED");
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

    private void assertLegacyTerminalCheckpointRejected(String stage) {
        SqliteTaskRepository tasks = repository("legacy-" + stage + ".db");
        UUID taskId = saveTask(tasks);
        PersistedTask current = tasks.findRecordById(taskId).orElseThrow();
        tasks.save(new PersistedTask(
                current.snapshot(),
                Optional.of("quarry|" + stage),
                0
        ));
        SqliteAutomationCheckpointStore store = new SqliteAutomationCheckpointStore(tasks);

        assertThrows(IllegalArgumentException.class, () -> store.load(taskId));
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
