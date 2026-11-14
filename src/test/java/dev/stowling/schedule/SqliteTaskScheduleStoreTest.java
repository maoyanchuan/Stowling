package dev.stowling.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.persistence.SqliteTaskScheduleStore;
import dev.stowling.persistence.StowlingDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTaskScheduleStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsScheduleStateInIndependentTable() {
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve("schedules.db"));
        SqliteTaskScheduleStore store = new SqliteTaskScheduleStore(database);
        TaskKey key = new TaskKey(TaskKey.AUTOMATION, "daily");
        UUID runId = UUID.randomUUID();
        TaskScheduleRecord record = new TaskScheduleRecord(
                key,
                true,
                "QUEUED",
                TaskPriority.HIGH,
                Instant.parse("2026-11-15T02:00:00Z"),
                Instant.parse("2026-11-14T02:00:00Z"),
                null,
                null,
                true,
                runId,
                7L,
                true,
                "queued",
                false,
                Instant.parse("2026-11-15T01:00:00Z")
        );

        store.save(record);

        assertEquals(List.of(record), store.load());
    }
}
