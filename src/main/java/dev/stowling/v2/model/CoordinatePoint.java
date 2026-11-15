package dev.stowling.v2.model;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CoordinatePoint(
        UUID id,
        String name,
        int x,
        int y,
        int z,
        String dimension,
        Integer radius
) {
    private static final Pattern DIMENSION_ID =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_DIMENSION_LENGTH = 256;
    private static final int MAX_RADIUS = 128;

    public CoordinatePoint {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        dimension = normalizeDimension(dimension);
        radius = requireRadius(radius);
    }

    public CoordinatePoint(UUID id, String name, int x, int y, int z) {
        this(id, name, x, y, z, null, null);
    }

    public CoordinatePoint withName(String newName) {
        return new CoordinatePoint(id, newName, x, y, z, dimension, radius);
    }

    public CoordinatePoint withPosition(int newX, int newY, int newZ) {
        return new CoordinatePoint(id, name, newX, newY, newZ, dimension, radius);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException("name must contain 1 to 80 characters");
        }
        return trimmed;
    }

    private static String normalizeDimension(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_DIMENSION_LENGTH
                || !DIMENSION_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("dimension must be a valid identifier");
        }
        return trimmed;
    }

    private static Integer requireRadius(Integer value) {
        if (value != null && (value < 0 || value > MAX_RADIUS)) {
            throw new IllegalArgumentException(
                    "radius must be between 0 and " + MAX_RADIUS
            );
        }
        return value;
    }
}
