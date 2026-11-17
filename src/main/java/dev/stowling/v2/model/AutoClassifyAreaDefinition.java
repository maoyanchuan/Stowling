package dev.stowling.v2.model;

import dev.stowling.v2.category.CategoryMarkerSource;
import java.util.List;
import java.util.Objects;

public record AutoClassifyAreaDefinition(
        String categoryZoneRef,
        String ruleId,
        List<CategoryMarkerSource> markerSources
) {
    public AutoClassifyAreaDefinition {
        categoryZoneRef = requireZoneReference(categoryZoneRef);
        ruleId = requireText(ruleId, "ruleId");
        markerSources = List.copyOf(
                Objects.requireNonNull(markerSources, "markerSources")
        );
        if (markerSources.isEmpty()) {
            throw new IllegalArgumentException(
                    "markerSources must contain at least one source"
            );
        }
        if (markerSources.stream().distinct().count() != markerSources.size()) {
            throw new IllegalArgumentException("markerSources must be unique");
        }
    }

    private static String requireZoneReference(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "categoryZoneRef is required"
            );
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() != ResourceType.CATEGORY_ZONE) {
            throw new IllegalArgumentException(
                    "categoryZoneRef must reference CATEGORY_ZONE"
            );
        }
        return reference.encode();
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
