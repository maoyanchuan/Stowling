package dev.stowling.v2.runtime.modules;

import java.util.Objects;

public record TaskModuleResult(
        TaskModuleState state,
        String message
) {
    public TaskModuleResult {
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
    }

    public static TaskModuleResult running(String message) {
        return new TaskModuleResult(TaskModuleState.RUNNING, message);
    }

    public static TaskModuleResult waiting(String message) {
        return new TaskModuleResult(TaskModuleState.WAITING, message);
    }

    public static TaskModuleResult succeeded(String message) {
        return new TaskModuleResult(TaskModuleState.SUCCEEDED, message);
    }

    public static TaskModuleResult failed(String message) {
        return new TaskModuleResult(TaskModuleState.FAILED, message);
    }
}
