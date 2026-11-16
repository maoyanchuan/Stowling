package dev.stowling.v2.control;

import java.util.List;

public final class TaskInUseException extends RuntimeException {
    private final List<String> taskNames;

    public TaskInUseException(List<String> taskNames) {
        super("task is used by other tasks: "
                + String.join(", ", taskNames));
        this.taskNames = List.copyOf(taskNames);
    }

    public List<String> taskNames() {
        return taskNames;
    }

    public List<String> triggerNames() {
        return taskNames;
    }
}
