package dev.stowling.automation;

import java.util.Objects;
import java.util.Optional;

public record ContainerAttemptResult(
        ContainerAttemptStatus status,
        long observedRevision,
        Optional<ContainerView> sourceAfter,
        Optional<ContainerView> destinationAfter,
        String detail
) {
    public ContainerAttemptResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sourceAfter, "sourceAfter");
        Objects.requireNonNull(destinationAfter, "destinationAfter");
        Objects.requireNonNull(detail, "detail");
        if (observedRevision < 0) {
            throw new IllegalArgumentException("observedRevision must not be negative");
        }
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
