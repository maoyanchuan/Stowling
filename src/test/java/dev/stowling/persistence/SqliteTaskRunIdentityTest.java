package dev.stowling.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteTaskRunIdentityTest {
    @TempDir
    Path directory;

    @Test
    void keepsDefinitionIdentityWithoutKeepingAnExecutionCheckpoint() {
        StowlingDatabase database = new StowlingDatabase(directory.resolve("identity.db"));
        SqliteTaskRepository repository = new SqliteTaskRepository(database);
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        repository.save(new TaskSnapshot(runId, TaskKind.AUTOMATION, TaskStatus.PENDING,
                AutomationState.IDLE_WAITING, now, now, Optional.empty()));
        TaskDefinition definition = new TaskDefinition("AUTOMATION", "one", "One",
                TaskSettings.defaults(), null, List.of(new AutomationStep("wait",
                        AutomationStepType.WAIT, Map.of("seconds", "1"))));
        repository.saveRunDefinition(runId, definition);
        repository.save(new TaskSnapshot(runId, TaskKind.AUTOMATION, TaskStatus.CANCELLED,
                AutomationState.IDLE_WAITING, now, now, Optional.empty()));

        SqliteTaskRepository reopened = new SqliteTaskRepository(database);
        assertEquals(definition.key(), reopened.runDefinition(runId).orElseThrow());
        assertTrue(reopened.findRecordById(runId).orElseThrow().recoveryCheckpoint().isEmpty());
        assertTrue(reopened.runDefinition(UUID.randomUUID()).isEmpty());
    }
}
