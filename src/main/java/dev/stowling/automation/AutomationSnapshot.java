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
        int remainingDeliveries
) {
    public AutomationSnapshot {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(stage, "stage");
        pauseReason = Objects.requireNonNull(pauseReason, "pauseReason");
        resumeStage = Objects.requireNonNull(resumeStage, "resumeStage");
        activeDelivery = Objects.requireNonNull(activeDelivery, "activeDelivery");
        if (observedInputCount < 0 || remainingDeliveries < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
