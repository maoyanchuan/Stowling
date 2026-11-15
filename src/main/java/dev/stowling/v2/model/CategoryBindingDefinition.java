package dev.stowling.v2.model;

import java.util.Objects;
import java.util.UUID;

public record CategoryBindingDefinition(
        UUID id,
        AreaBounds markerArea,
        AreaBounds storageArea,
        String ruleId
) {
    public CategoryBindingDefinition {
        id = Objects.requireNonNull(id, "id");
        markerArea = Objects.requireNonNull(markerArea, "markerArea");
        storageArea = Objects.requireNonNull(storageArea, "storageArea");
        ruleId = requireText(ruleId, "ruleId");
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
