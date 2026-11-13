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
        Optional<String> planReference,
        Set<String> observedInputKeys,
        Optional<Instant> idleSince,
        Map<String, Integer> inventoryBaseline,
        Optional<String> selectedCacheId,
        int classificationCacheIndex
) {
    public AutomationCheckpoint {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(stage, "stage");
        planReference = Objects.requireNonNull(planReference, "planReference");
        observedInputKeys = Set.copyOf(Objects.requireNonNull(
                observedInputKeys,
                "observedInputKeys"
        ));
        idleSince = Objects.requireNonNull(idleSince, "idleSince");
        inventoryBaseline = Map.copyOf(Objects.requireNonNull(
                inventoryBaseline,
                "inventoryBaseline"
        ));
        selectedCacheId = Objects.requireNonNull(selectedCacheId, "selectedCacheId");
        if (classificationCacheIndex < 0) {
            throw new IllegalArgumentException("classificationCacheIndex must not be negative");
        }
        if (stage == PickupStage.PAUSED || stage == PickupStage.FAILED) {
            throw new IllegalArgumentException("checkpoint must store a resumable stage");
        }
    }

    public AutomationCheckpoint(
            UUID taskId,
            PickupStage stage,
            Optional<String> planReference,
            Set<String> observedInputKeys,
            Optional<Instant> idleSince,
            Map<String, Integer> inventoryBaseline
    ) {
        this(
                taskId,
                stage,
                planReference,
                observedInputKeys,
                idleSince,
                inventoryBaseline,
                Optional.empty(),
                0
        );
    }

    public AutomationCheckpoint(
            UUID taskId,
            PickupStage stage,
            Set<String> observedInputKeys,
            Optional<Instant> idleSince,
            Map<String, Integer> inventoryBaseline
    ) {
        this(
                taskId,
                stage,
                Optional.empty(),
                observedInputKeys,
                idleSince,
                inventoryBaseline,
                Optional.empty(),
                0
        );
    }
}
