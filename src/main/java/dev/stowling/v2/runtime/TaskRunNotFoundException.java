package dev.stowling.v2.runtime;

public final class TaskRunNotFoundException extends RuntimeException {
    public TaskRunNotFoundException() {
        super("running task not found");
    }
}
