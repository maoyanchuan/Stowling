package dev.stowling.v2.control;

import java.util.List;

public final class CoordinateInUseException extends RuntimeException {
    private final List<String> taskNames;

    public CoordinateInUseException(List<String> taskNames) {
        super("coordinate is referenced by tasks: " + String.join(", ", taskNames));
        this.taskNames = List.copyOf(taskNames);
    }

    public List<String> taskNames() {
        return taskNames;
    }
}
