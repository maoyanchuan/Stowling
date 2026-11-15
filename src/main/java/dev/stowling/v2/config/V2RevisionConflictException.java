package dev.stowling.v2.config;

public final class V2RevisionConflictException extends RuntimeException {
    public V2RevisionConflictException() {
        super("configuration was modified outside this request");
    }
}
