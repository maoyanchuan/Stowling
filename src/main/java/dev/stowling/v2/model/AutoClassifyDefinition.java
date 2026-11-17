package dev.stowling.v2.model;

import dev.stowling.v2.category.CategoryMarkerSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AutoClassifyDefinition(
        UUID id,
        String name,
        boolean enabled,
        boolean collectionEnabled,
        boolean classificationEnabled,
        String detectionCoordinateRef,
        int pickupRadius,
        String cacheRegionRef,
        String categoryZoneRef,
        List<CategoryMarkerSource> markerSources,
        TaskPriority collectionPriority
) {
    public static final int DEFAULT_PICKUP_RADIUS = 8;
    public static final int MIN_PICKUP_RADIUS = 1;
    public static final int MAX_PICKUP_RADIUS = 64;

    public AutoClassifyDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        detectionCoordinateRef = requireReference(
                detectionCoordinateRef,
                ResourceType.COORDINATE,
                "detectionCoordinateRef"
        );
        if (pickupRadius < MIN_PICKUP_RADIUS
                || pickupRadius > MAX_PICKUP_RADIUS) {
            throw new IllegalArgumentException(
                    "pickupRadius must be between "
                            + MIN_PICKUP_RADIUS + " and " + MAX_PICKUP_RADIUS
            );
        }
        cacheRegionRef = requireReference(
                cacheRegionRef,
                ResourceType.REGION,
                "cacheRegionRef"
        );
        categoryZoneRef = requireReference(
                categoryZoneRef,
                ResourceType.CATEGORY_ZONE,
                "categoryZoneRef"
        );
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
        collectionPriority = Objects.requireNonNull(
                collectionPriority,
                "collectionPriority"
        );
        if (collectionPriority != TaskPriority.NORMAL
                && collectionPriority != TaskPriority.URGENT) {
            throw new IllegalArgumentException(
                    "collectionPriority must be NORMAL or URGENT"
            );
        }
    }

    public UUID classificationTaskId() {
        return derivedId("classification");
    }

    public UUID interruptionTaskId() {
        return derivedId("interruption");
    }

    public UUID collectionTriggerId() {
        return derivedId("collection-trigger");
    }

    public UUID classificationTriggerId() {
        return derivedId("classification-trigger");
    }

    public UUID derivedId(String role) {
        return UUID.nameUUIDFromBytes(
                (id + ":" + role).getBytes(StandardCharsets.UTF_8)
        );
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

    private static String requireReference(
            String value,
            ResourceType expected,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() != expected) {
            throw new IllegalArgumentException(
                    field + " must reference " + expected.name()
            );
        }
        return reference.encode();
    }
}
