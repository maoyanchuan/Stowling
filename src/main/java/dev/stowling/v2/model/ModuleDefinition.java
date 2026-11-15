package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;

public record ModuleDefinition(
        String id,
        String name,
        String description,
        TaskModuleLevel level,
        List<ModuleParameter> parameters
) {
    public ModuleDefinition {
        id = requireText(id, "id");
        name = requireText(name, "name");
        description = Objects.requireNonNull(description, "description").trim();
        level = Objects.requireNonNull(level, "level");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public ModuleDefinition(
            String id,
            String name,
            String description,
            List<ModuleParameter> parameters
    ) {
        this(
                id,
                name,
                description,
                TaskModuleLevel.BASIC,
                parameters
        );
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
