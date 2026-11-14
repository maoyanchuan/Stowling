package dev.stowling.schedule;

import java.util.Objects;
import java.util.Set;

public record RunOutcome(String state, String message) {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String UNSAFE = "UNSAFE";

    private static final Set<String> STATES = Set.of(RUNNING, SUCCEEDED, FAILED, UNSAFE);

    public RunOutcome {
        Objects.requireNonNull(state, "state");
        if (!STATES.contains(state)) {
            throw new IllegalArgumentException("unsupported run outcome: " + state);
        }
        message = message == null ? "" : message;
    }

    public boolean isFinal() {
        return !RUNNING.equals(state);
    }
}
