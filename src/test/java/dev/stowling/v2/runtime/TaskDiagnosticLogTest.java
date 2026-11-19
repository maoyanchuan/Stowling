package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskDiagnosticLogTest {
    @Test
    void runExportIncludesItsCleanupButNeverAnotherExecution() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        UUID selected = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        append(log, selected, "selected-task");
        append(log, other, "other-task");
        log.append(selected, "CLEANUP", "1", "CLEANUP", "ERROR", "cleanup",
                "selected-cleanup", 100, false);

        var batch = log.readRun(selected);
        assertEquals(2, batch.events().size());
        assertTrue(batch.events().stream().allMatch(event -> selected.equals(event.runId())));
        assertEquals("CLEANUP", batch.events().getLast().phase());
        assertEquals(1, batch.firstAvailable());
        assertEquals(3, batch.latestSequence());
        assertTrue(log.readRun(UUID.randomUUID()).events().isEmpty());
    }

    @Test
    void accidentalCredentialsInStatusMessagesAreRedactedBeforeRetention() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        append(log, UUID.randomUUID(), "Bearer private-token; password=private-password");
        String details = log.read(0, 10).events().getFirst().details();

        assertFalse(details.contains("private-token"));
        assertFalse(details.contains("private-password"));
        assertTrue(details.contains("[redacted]"));
    }
    @Test
    void unchangedWaitsHaveHeartbeatAndChangedQuantitiesAreRecordedImmediately() {
        MutableClock clock = new MutableClock();
        TaskDiagnosticLog log = new TaskDiagnosticLog(20, clock);
        UUID owner = UUID.randomUUID();
        append(log, owner, "planned=64; actual=0");
        for (int tick = 0; tick < 19; tick++) append(log, owner, "planned=64; actual=0");
        assertEquals(1, log.read(0, 20).events().size());
        clock.millis += 1000;
        append(log, owner, "planned=64; actual=0");
        append(log, owner, "planned=64; actual=32");
        var events = log.read(0, 20).events();
        assertEquals(3, events.size());
        assertEquals(19, events.get(1).repeated());
        assertEquals("planned=64; actual=32", events.getLast().details());
    }

    @Test
    void rollingHistoryReportsGapsAndCursorPagesWithoutLosingTheFinalPage() {
        TaskDiagnosticLog log = new TaskDiagnosticLog(3, Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        for (int event = 0; event < 5; event++) append(log, owner, "count=" + event);
        var first = log.read(0, 2);
        assertTrue(first.truncated());
        assertEquals(3, first.firstAvailable());
        assertEquals(4, first.nextSequence());
        var last = log.read(first.nextSequence(), 2);
        assertFalse(last.truncated());
        assertEquals(5, last.nextSequence());
        assertEquals(1, last.events().size());
        assertTrue(log.read(5, 2).events().isEmpty());
        assertEquals(2, log.read(999, 2).events().size());
    }

    @Test
    void nestedScopesRestoreTheParentAndDoNotAttachUnscopedEventsToFinishedTasks() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        UUID owner = UUID.randomUUID();
        TaskTrace.within(log, owner, "TASK", "2.1", "COLLECT", () -> {
            TaskTrace.event("CALL", "outer", "");
            TaskTrace.within(log, owner, "CLEANUP", "1", "STORE", () -> {
                TaskTrace.event("WAIT", "inner", "cache");
                return null;
            });
            TaskTrace.event("RETURN", "outer", "");
            return null;
        });
        TaskTrace.event("CALL", "unscoped", "");
        var events = log.read(0, 10).events();
        assertEquals(3, events.size());
        assertEquals("TASK", events.getLast().phase());
        assertEquals("2.1", events.getLast().step());
        assertFalse(TaskTrace.active());
    }

    private static void append(TaskDiagnosticLog log, UUID owner, String details) {
        log.append(owner, "TASK", "2.2", "DEPOSIT", "WAIT", "confirm", details, 100, true);
    }

    private static final class MutableClock extends Clock {
        private long millis;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
