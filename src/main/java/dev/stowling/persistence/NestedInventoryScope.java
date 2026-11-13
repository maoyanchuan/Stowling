package dev.stowling.persistence;

import dev.stowling.inventory.InventoryItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NestedInventoryScope(
        String scopeId,
        Optional<String> parentScopeId,
        List<InventoryItem> items
) {
    public NestedInventoryScope {
        scopeId = requireText(scopeId, "scopeId");
        parentScopeId = Objects.requireNonNull(parentScopeId, "parentScopeId");
        parentScopeId = parentScopeId.map(value -> requireText(value, "parentScopeId"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
