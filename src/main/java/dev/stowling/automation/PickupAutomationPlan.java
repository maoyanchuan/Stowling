package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PickupAutomationPlan(
        UUID taskId,
        PickupSite pickupSite,
        List<StorageRoute> storageRoutes,
        ContainerRef overflowContainer,
        int inventoryReserveSlots,
        int maxContainerRetries,
        Duration containerAckTimeout,
        Duration navigationTimeout
) {
    public PickupAutomationPlan {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(pickupSite, "pickupSite");
        storageRoutes = List.copyOf(Objects.requireNonNull(storageRoutes, "storageRoutes"));
        Objects.requireNonNull(overflowContainer, "overflowContainer");
        Objects.requireNonNull(containerAckTimeout, "containerAckTimeout");
        Objects.requireNonNull(navigationTimeout, "navigationTimeout");
        if (inventoryReserveSlots < 0 || inventoryReserveSlots >= 36) {
            throw new IllegalArgumentException("inventoryReserveSlots must be between 0 and 35");
        }
        if (maxContainerRetries < 0 || maxContainerRetries > 20) {
            throw new IllegalArgumentException("maxContainerRetries must be between 0 and 20");
        }
        if (containerAckTimeout.isNegative() || containerAckTimeout.isZero()) {
            throw new IllegalArgumentException("containerAckTimeout must be positive");
        }
        if (navigationTimeout.isNegative() || navigationTimeout.isZero()) {
            throw new IllegalArgumentException("navigationTimeout must be positive");
        }
    }
}
