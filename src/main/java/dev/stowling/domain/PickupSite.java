package dev.stowling.domain;

import java.time.Duration;
import java.util.Objects;

public record PickupSite(
        String id,
        String dimension,
        BlockPosition center,
        int radius,
        ContainerRef cacheContainer,
        Duration idleDelay
) {
    public PickupSite {
        id = DomainChecks.requireText(id, "pickup site id");
        dimension = DomainChecks.requireText(dimension, "dimension");
        center = Objects.requireNonNull(center, "center");
        cacheContainer = Objects.requireNonNull(cacheContainer, "cacheContainer");
        idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (radius < 1 || radius > 128) {
            throw new IllegalArgumentException("radius must be between 1 and 128");
        }
        if (!dimension.equals(cacheContainer.dimension())) {
            throw new IllegalArgumentException("cache container dimension must match pickup site");
        }
        if (idleDelay.isNegative()) {
            throw new IllegalArgumentException("idleDelay must not be negative");
        }
    }
}
