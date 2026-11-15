package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record CategoryContainerOverrideDefinition(
        UUID id,
        BlockPosition storagePosition,
        CategoryContainerOverrideMode mode,
        List<String> itemIds
) {
    public CategoryContainerOverrideDefinition {
        id = Objects.requireNonNull(id, "id");
        storagePosition = Objects.requireNonNull(
                storagePosition,
                "storagePosition"
        );
        mode = Objects.requireNonNull(mode, "mode");
        itemIds = normalizeItemIds(itemIds);
        if (itemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "container override requires at least one item id"
            );
        }
    }

    private static List<String> normalizeItemIds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> requireText(value, "override item id"))
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to 128 characters"
            );
        }
        return trimmed;
    }
}
