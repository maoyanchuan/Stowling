package dev.stowling.v2.control;

import java.util.List;

public final class TaskInUseException extends RuntimeException {
    private final List<String> triggerNames;

    public TaskInUseException(List<String> triggerNames) {
        super("task is used by triggers: "
                + String.join(", ", triggerNames));
        this.triggerNames = List.copyOf(triggerNames);
    }

    public List<String> triggerNames() {
        return triggerNames;
    }
}
