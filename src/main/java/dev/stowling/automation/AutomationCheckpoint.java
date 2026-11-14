package dev.stowling.automation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists high-level workflow progress without any unconfirmed slot click.
 */
public record AutomationCheckpoint(
        UUID taskId,
        PickupStage stage,
        Optional<String> workflowId,
        Optional<String> regionOverride,
        Optional<String> currentRegionId,
        long round,
        Set<String> failedCacheIds,
        Optional<Instant> workflowIdleSince,
        Map<String, Instant> regionIdleSince,
        Set<String> observedInputKeys,
        Map<String, Integer> inventoryBaseline,
        Optional<String> selectedCacheId,
        int classificationCacheIndex,
        boolean classificationAfterRound
) {
    public AutomationCheckpoint {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(stage, "stage");
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        regionOverride = Objects.requireNonNull(regionOverride, "regionOverride");
        currentRegionId = Objects.requireNonNull(currentRegionId, "currentRegionId");
        failedCacheIds = Set.copyOf(Objects.requireNonNull(
                failedCacheIds,
                "failedCacheIds"
        ));
        workflowIdleSince = Objects.requireNonNull(
                workflowIdleSince,
                "workflowIdleSince"
        );
        regionIdleSince = Map.copyOf(Objects.requireNonNull(
                regionIdleSince,
                "regionIdleSince"
        ));
        observedInputKeys = Set.copyOf(Objects.requireNonNull(
                observedInputKeys,
                "observedInputKeys"
        ));
        inventoryBaseline = Map.copyOf(Objects.requireNonNull(
                inventoryBaseline,
                "inventoryBaseline"
        ));
        selectedCacheId = Objects.requireNonNull(selectedCacheId, "selectedCacheId");
        if (round < 0) {
            throw new IllegalArgumentException("round must not be negative");
        }
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
            Map<String, Integer> inventoryBaseline,
            Optional<String> selectedCacheId,
            int classificationCacheIndex
    ) {
        this(
                taskId,
                stage,
                planReference,
                Optional.empty(),
                Optional.empty(),
                0,
                Set.of(),
                idleSince,
                Map.of(),
                observedInputKeys,
                inventoryBaseline,
                selectedCacheId,
                classificationCacheIndex,
                false
        );
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
                inventoryBaseline
        );
    }

    public Optional<String> planReference() {
        return workflowId;
    }

    public Optional<Instant> idleSince() {
        if (currentRegionId.isPresent()) {
            Instant value = regionIdleSince.get(currentRegionId.orElseThrow());
            if (value != null) {
                return Optional.of(value);
            }
        }
        return workflowIdleSince;
    }
}
