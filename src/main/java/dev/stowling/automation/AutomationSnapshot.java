package dev.stowling.automation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AutomationSnapshot(
        UUID taskId,
        PickupStage stage,
        Optional<AutomationPauseReason> pauseReason,
        Optional<PickupStage> resumeStage,
        Optional<DeliveryIntent> activeDelivery,
        int observedInputCount,
        int remainingDeliveries,
        String workflowId,
        Optional<String> currentRegionId,
        long round
) {
    public AutomationSnapshot {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(stage, "stage");
        pauseReason = Objects.requireNonNull(pauseReason, "pauseReason");
        resumeStage = Objects.requireNonNull(resumeStage, "resumeStage");
        activeDelivery = Objects.requireNonNull(activeDelivery, "activeDelivery");
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        currentRegionId = Objects.requireNonNull(currentRegionId, "currentRegionId");
        if (observedInputCount < 0 || remainingDeliveries < 0 || round < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    public AutomationSnapshot(
            UUID taskId,
            PickupStage stage,
            Optional<AutomationPauseReason> pauseReason,
            Optional<PickupStage> resumeStage,
            Optional<DeliveryIntent> activeDelivery,
            int observedInputCount,
            int remainingDeliveries
    ) {
        this(
                taskId,
                stage,
                pauseReason,
                resumeStage,
                activeDelivery,
                observedInputCount,
                remainingDeliveries,
                "legacy",
                Optional.empty(),
                0
        );
    }
}
