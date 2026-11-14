package dev.stowling.schedule;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskScheduleInfo(
        TaskKey key,
        boolean enabled,
        String state,
        TaskPriority priority,
        Instant nextRunAt,
        UUID runId,
        String message,
        Instant lastFinishedAt
) {
    public TaskScheduleInfo {
        key = Objects.requireNonNull(key, "key");
        state = requireText(state, "state");
        priority = Objects.requireNonNull(priority, "priority");
        message = message == null ? "" : message;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
