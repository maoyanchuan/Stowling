package dev.stowling.automation;

import java.time.Instant;
import java.util.Objects;

public record ContainerAttempt(
        String attemptId,
        ContainerTransfer transfer,
        long baselineRevision,
        Instant startedAt
) {
    public ContainerAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        if (attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
        }
        Objects.requireNonNull(transfer, "transfer");
        if (baselineRevision < 0) {
            throw new IllegalArgumentException("baselineRevision must not be negative");
        }
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
