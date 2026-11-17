package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CategoryZoneDefinition(
        UUID id,
        String name,
        List<CategoryBindingDefinition> bindings,
        CategoryZoneExpansionDefinition expansion
) {
    public CategoryZoneDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        bindings = List.copyOf(
                Objects.requireNonNull(bindings, "bindings")
        );
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "category zone must contain at least one binding"
            );
        }
    }

    public CategoryZoneDefinition(
            UUID id,
            String name,
            List<CategoryBindingDefinition> bindings
    ) {
        this(id, name, bindings, null);
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
