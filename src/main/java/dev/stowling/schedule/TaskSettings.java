package dev.stowling.schedule;

import java.util.Objects;

public record TaskSettings(
        TaskPriority priority,
        TaskTrigger trigger,
        boolean autoStartOnRestart,
        TaskInterruption interruption
) {
    public TaskSettings {
        priority = Objects.requireNonNull(priority, "task priority");
        trigger = Objects.requireNonNull(trigger, "task trigger");
        interruption = Objects.requireNonNull(interruption, "task interruption");
    }

    public static TaskSettings defaults() {
        return new TaskSettings(
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                false,
                TaskInterruption.none()
        );
    }
}
