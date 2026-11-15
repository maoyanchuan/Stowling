package dev.stowling.v2.runtime;

public final class TaskRunBusyException extends RuntimeException {
    public TaskRunBusyException() {
        super("another task is already running");
    }
}
