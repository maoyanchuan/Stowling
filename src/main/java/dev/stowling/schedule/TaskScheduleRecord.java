package dev.stowling.schedule;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskScheduleRecord(
        TaskKey key,
        boolean enabled,
        String state,
        TaskPriority priority,
        Instant nextRunAt,
        Instant lastFinishedAt,
        UUID runId,
        TaskKey runTaskKey,
        boolean queued,
        UUID queuedRunId,
        long queueOrder,
        boolean rerunPending,
        String message,
        boolean unsafeLocked,
        Instant lastProbeAt
) {
    public TaskScheduleRecord {
        key = Objects.requireNonNull(key, "key");
        state = requireText(state, "state");
        priority = Objects.requireNonNull(priority, "priority");
        message = message == null ? "" : message;
        if (queueOrder < 0) {
            throw new IllegalArgumentException("queueOrder must not be negative");
        }
        if (!queued && queuedRunId != null) {
            throw new IllegalArgumentException("queuedRunId requires queued");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
