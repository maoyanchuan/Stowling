package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.Optional;

/**
 * Extensible source of a concrete container reference.
 *
 * <p>Future warehouse or discovery-backed sources can resolve a container
 * without changing configured-container callers.
 */
public interface ContainerSource {
    String id();

    String dimension();

    Optional<ContainerRef> resolve();
}
