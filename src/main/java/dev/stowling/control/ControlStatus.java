package dev.stowling.control;

import java.time.Instant;
import java.util.Objects;

public record ControlStatus(boolean paused, String state, Instant observedAt) {
    public ControlStatus {
        state = requireText(state, "state");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
