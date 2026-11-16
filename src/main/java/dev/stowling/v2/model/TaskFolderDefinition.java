package dev.stowling.v2.model;

import java.util.Objects;
import java.util.UUID;

public record TaskFolderDefinition(UUID id, String name) {
    public TaskFolderDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException(
                    "name must contain 1 to 80 characters"
            );
        }
        return trimmed;
    }
}
