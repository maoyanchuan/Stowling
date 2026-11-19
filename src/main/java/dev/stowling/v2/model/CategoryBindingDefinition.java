package dev.stowling.v2.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record CategoryBindingDefinition(
        UUID id,
        AreaBounds markerArea,
        AreaBounds storageArea,
        String ruleId,
        List<String> ignoredMarkerBlockIds,
        List<CategoryContainerOverrideDefinition> containerOverrides,
        AreaBounds standingArea,
        StoragePolicy storagePolicy,
        boolean wholeStorage
) {
    public CategoryBindingDefinition {
        storagePolicy = storagePolicy == null ? StoragePolicy.DEFAULT : storagePolicy;
        id = Objects.requireNonNull(id, "id");
        markerArea = Objects.requireNonNull(markerArea, "markerArea");
        storageArea = Objects.requireNonNull(storageArea, "storageArea");
        ruleId = requireText(ruleId, "ruleId");
        ignoredMarkerBlockIds = normalizeBlockIds(ignoredMarkerBlockIds);
        containerOverrides = containerOverrides == null
                ? List.of()
                : List.copyOf(containerOverrides);
    }

    public CategoryBindingDefinition(
            UUID id, AreaBounds markerArea, AreaBounds storageArea, String ruleId,
            List<String> ignoredMarkerBlockIds,
            List<CategoryContainerOverrideDefinition> containerOverrides, AreaBounds standingArea,
            StoragePolicy storagePolicy
    ) {
        this(id, markerArea, storageArea, ruleId, ignoredMarkerBlockIds,
                containerOverrides, standingArea, storagePolicy, false);
    }

    public CategoryBindingDefinition(
            UUID id, AreaBounds markerArea, AreaBounds storageArea, String ruleId,
            List<String> ignoredMarkerBlockIds,
            List<CategoryContainerOverrideDefinition> containerOverrides, AreaBounds standingArea
    ) {
        this(id, markerArea, storageArea, ruleId, ignoredMarkerBlockIds,
                containerOverrides, standingArea, StoragePolicy.DEFAULT);
    }

    public CategoryBindingDefinition(
            UUID id,
            AreaBounds markerArea,
            AreaBounds storageArea,
            String ruleId,
            List<String> ignoredMarkerBlockIds,
            List<CategoryContainerOverrideDefinition> containerOverrides
    ) {
        this(id, markerArea, storageArea, ruleId, ignoredMarkerBlockIds, containerOverrides, null);
    }

    public CategoryBindingDefinition(
            UUID id,
            AreaBounds markerArea,
            AreaBounds storageArea,
            String ruleId,
            List<String> ignoredMarkerBlockIds
    ) {
        this(
                id,
                markerArea,
                storageArea,
                ruleId,
                ignoredMarkerBlockIds,
                List.of()
        );
    }

    public CategoryBindingDefinition(
            UUID id,
            AreaBounds markerArea,
            AreaBounds storageArea,
            String ruleId
    ) {
        this(id, markerArea, storageArea, ruleId, List.of(), List.of());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }

    private static List<String> normalizeBlockIds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> requireText(value, "ignored block id")
                        .toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
