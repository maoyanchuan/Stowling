package dev.stowling.v2.category;

import java.util.Objects;

public record CategoryRuleDefinition(
        String id,
        String name,
        String description
) {
    public CategoryRuleDefinition {
        id = requireText(id, "id");
        name = requireText(name, "name");
        description = requireText(description, "description");
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
