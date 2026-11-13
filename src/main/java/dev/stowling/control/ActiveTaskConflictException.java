package dev.stowling.control;

public final class ActiveTaskConflictException extends RuntimeException {
    public ActiveTaskConflictException() {
        super("Another pickup task is active");
    }
}
