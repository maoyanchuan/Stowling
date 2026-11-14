package dev.stowling.domain;

import java.time.Duration;
import java.util.Objects;

public record PickupRegionBinding(
        String regionId,
        int priority,
        Duration idleDelay
) {
    public PickupRegionBinding {
        regionId = DomainChecks.requireText(regionId, "regionId");
        idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (idleDelay.isNegative()) {
            throw new IllegalArgumentException("idleDelay must not be negative");
        }
    }
}
