package dev.stowling.config;

public final class ConfigRevisionConflictException extends RuntimeException {
    public ConfigRevisionConflictException() {
        super("Configuration revision no longer matches the file");
    }
}
