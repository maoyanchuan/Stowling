package dev.stowling.v2.model;

import java.util.Objects;
import java.util.UUID;

public record RegionDefinition(
        UUID id,
        String name,
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ
) {
    public RegionDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException("name must contain 1 to 80 characters");
        }
        return trimmed;
    }
}
