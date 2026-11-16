package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;

public record ModuleDefinition(
        String id,
        String name,
        String description,
        TaskModuleKind kind,
        TaskModuleLevel level,
        List<ModuleParameter> parameters,
        boolean deprecated,
        List<String> composedOf
) {
    public ModuleDefinition {
        id = requireText(id, "id");
        name = requireText(name, "name");
        description = Objects.requireNonNull(description, "description").trim();
        kind = Objects.requireNonNull(kind, "kind");
        level = Objects.requireNonNull(level, "level");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        composedOf = List.copyOf(
                Objects.requireNonNull(composedOf, "composedOf")
        );
    }

    public ModuleDefinition(
            String id,
            String name,
            String description,
            TaskModuleKind kind,
            TaskModuleLevel level,
            List<ModuleParameter> parameters
    ) {
        this(
                id,
                name,
                description,
                kind,
                level,
                parameters,
                false,
                List.of()
        );
    }

    public ModuleDefinition(
            String id,
            String name,
            String description,
            TaskModuleKind kind,
            TaskModuleLevel level,
            List<ModuleParameter> parameters,
            boolean deprecated
    ) {
        this(
                id,
                name,
                description,
                kind,
                level,
                parameters,
                deprecated,
                List.of()
        );
    }

    public ModuleDefinition(
            String id,
            String name,
            String description,
            TaskModuleLevel level,
            List<ModuleParameter> parameters,
            boolean deprecated
    ) {
        this(
                id,
                name,
                description,
                TaskModuleKind.ACTION,
                level,
                parameters,
                deprecated,
                List.of()
        );
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
                TaskModuleKind.ACTION,
                TaskModuleLevel.BASIC,
                parameters,
                false,
                List.of()
        );
    }

    public ModuleDefinition(
            String id,
            String name,
            String description,
            TaskModuleLevel level,
            List<ModuleParameter> parameters
    ) {
        this(
                id,
                name,
                description,
                TaskModuleKind.ACTION,
                level,
                parameters,
                false,
                List.of()
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
