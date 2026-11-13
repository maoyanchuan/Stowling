package dev.stowling.control;

public final class ActiveTaskConflictException extends RuntimeException {
    public ActiveTaskConflictException() {
        super("Coordinates cannot change while a pickup task is active");
    }
}
