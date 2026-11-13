package dev.stowling.persistence;

import dev.stowling.task.TaskSnapshot;
import java.util.Objects;
import java.util.Optional;

public record PersistedTask(
        TaskSnapshot snapshot,
        Optional<String> recoveryCheckpoint,
        int retryCount
) {
    public PersistedTask {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        recoveryCheckpoint = Objects.requireNonNull(recoveryCheckpoint, "recoveryCheckpoint");
        recoveryCheckpoint = recoveryCheckpoint.map(PersistedTask::normalizeCheckpoint);
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
    }

    public static PersistedTask fromSnapshot(TaskSnapshot snapshot) {
        return new PersistedTask(snapshot, Optional.empty(), 0);
    }

    private static String normalizeCheckpoint(String checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String trimmed = checkpoint.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("recoveryCheckpoint must not be blank");
        }
        return trimmed;
    }
}
