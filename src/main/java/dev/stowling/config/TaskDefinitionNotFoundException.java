package dev.stowling.config;

import dev.stowling.schedule.TaskKey;
import java.util.Objects;

public final class TaskDefinitionNotFoundException extends RuntimeException {
    public TaskDefinitionNotFoundException(TaskKey key) {
        super("Task definition does not exist: " + Objects.requireNonNull(key, "key"));
    }
}
