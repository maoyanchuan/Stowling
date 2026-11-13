package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.Optional;

/**
 * Executes a high-level transfer and reports only server-confirmed outcomes.
 *
 * <p>An implementation returns {@link ContainerAttemptStatus#ACKNOWLEDGED} only
 * after a newer menu revision and the expected source/destination slot changes
 * have both been observed. It must not cache or replay low-level click packets.
 */
public interface ContainerInteraction {
    default void prepare(ContainerRef container) {
    }

    Optional<ContainerView> observe(ContainerRef container);

    ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision);

    ContainerAttemptResult poll(ContainerAttempt attempt);

    void cancel(ContainerAttempt attempt);
}
