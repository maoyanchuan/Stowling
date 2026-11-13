package dev.stowling.domain;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record PickupSite(
        String id,
        String dimension,
        BlockPosition center,
        int radius,
        List<ContainerRef> cacheContainers,
        Duration idleDelay
) {
    public PickupSite {
        id = DomainChecks.requireText(id, "pickup site id");
        dimension = DomainChecks.requireText(dimension, "dimension");
        center = Objects.requireNonNull(center, "center");
        Objects.requireNonNull(cacheContainers, "cacheContainers");
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (ContainerRef cacheContainer : cacheContainers) {
            Objects.requireNonNull(cacheContainer, "cacheContainer");
            if (!uniqueIds.add(cacheContainer.id())) {
                throw new IllegalArgumentException(
                        "cache container ids must be unique within a pickup site"
                );
            }
            if (!dimension.equals(cacheContainer.dimension())) {
                throw new IllegalArgumentException(
                        "cache container dimension must match pickup site"
                );
            }
        }
        cacheContainers = List.copyOf(cacheContainers);
        if (cacheContainers.isEmpty()) {
            throw new IllegalArgumentException("pickup site requires at least one cache container");
        }
        idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (radius < 1 || radius > 128) {
            throw new IllegalArgumentException("radius must be between 1 and 128");
        }
        if (idleDelay.isNegative()) {
            throw new IllegalArgumentException("idleDelay must not be negative");
        }
    }

    public PickupSite(
            String id,
            String dimension,
            BlockPosition center,
            int radius,
            ContainerRef cacheContainer,
            Duration idleDelay
    ) {
        this(id, dimension, center, radius, List.of(cacheContainer), idleDelay);
    }

    public ContainerRef cacheContainer() {
        return cacheContainers.getFirst();
    }
}
