package dev.stowling.domain;

import java.util.Objects;

public record NamedCache(
        String id,
        String name,
        String coordinateId,
        String dimension,
        BlockPosition position,
        ContainerType type
) {
    public NamedCache {
        id = DomainChecks.requireText(id, "cache id");
        name = DomainChecks.requireText(name, "cache name");
        dimension = DomainChecks.requireText(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
        type = Objects.requireNonNull(type, "type");
    }

    public NamedCache(
            String id,
            String name,
            String dimension,
            BlockPosition position,
            ContainerType type
    ) {
        this(id, name, null, dimension, position, type);
    }

    public ContainerRef toContainerRef() {
        return new ContainerRef(id, dimension, position, type);
    }
}
