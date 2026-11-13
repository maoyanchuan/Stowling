package dev.stowling.automation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists only high-level progress. Low-level clicks are deliberately absent.
 */
public record AutomationCheckpoint(
        UUID taskId,
        PickupStage stage,
        Set<String> observedInputKeys,
        Optional<Instant> idleSince,
        Map<String, Integer> inventoryBaseline
) {
    public AutomationCheckpoint {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(stage, "stage");
        observedInputKeys = Set.copyOf(Objects.requireNonNull(
                observedInputKeys,
                "observedInputKeys"
        ));
        idleSince = Objects.requireNonNull(idleSince, "idleSince");
        inventoryBaseline = Map.copyOf(Objects.requireNonNull(
                inventoryBaseline,
                "inventoryBaseline"
        ));
        if (stage == PickupStage.PAUSED || stage == PickupStage.FAILED) {
            throw new IllegalArgumentException("checkpoint must store a resumable stage");
        }
    }
}
