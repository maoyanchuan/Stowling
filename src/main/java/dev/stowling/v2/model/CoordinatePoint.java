package dev.stowling.v2.model;

import java.util.Objects;
import java.util.UUID;

public record CoordinatePoint(UUID id, String name, int x, int y, int z) {
    public CoordinatePoint {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
    }

    public CoordinatePoint withName(String newName) {
        return new CoordinatePoint(id, newName, x, y, z);
    }

    public CoordinatePoint withPosition(int newX, int newY, int newZ) {
        return new CoordinatePoint(id, name, newX, newY, newZ);
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
