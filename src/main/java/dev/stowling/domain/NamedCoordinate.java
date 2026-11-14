package dev.stowling.domain;

import java.util.Objects;

public record NamedCoordinate(
        String id,
        String name,
        String dimension,
        BlockPosition position
) {
    public NamedCoordinate {
        id = DomainChecks.requireText(id, "coordinate id");
        name = DomainChecks.requireText(name, "coordinate name");
        dimension = DomainChecks.requireText(dimension, "dimension");
        position = Objects.requireNonNull(position, "position");
    }
}
