package dev.stowling.automation;

import java.util.Objects;

public record ContainerCategory(
        ContainerTarget container,
        String itemId
) {
    public ContainerCategory {
        container = Objects.requireNonNull(container, "container");
        itemId = requireText(itemId, "itemId");
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
