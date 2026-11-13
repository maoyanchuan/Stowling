package dev.stowling.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record InventoryCapture(
        InventorySnapshot snapshot,
        List<Scope> nestedScopes
) {
    public InventoryCapture {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        nestedScopes = List.copyOf(Objects.requireNonNull(nestedScopes, "nestedScopes"));
    }

    public static InventoryCapture fromSlots(
            String sourceId,
            String containerId,
            String dimension,
            Instant capturedAt,
            InventoryFreshness freshness,
            List<SlotCapture> slots
    ) {
        Objects.requireNonNull(slots, "slots");
        List<InventoryItem> rootItems = new ArrayList<>();
        List<Scope> scopes = new ArrayList<>();
        for (SlotCapture slot : slots) {
            rootItems.add(new InventoryItem(slot.itemId(), slot.count(), slot.kind()));
            if (slot.kind() == InventoryItemKind.BOXED) {
                List<InventoryItem> boxedItems = slot.contents().stream()
                        .map(item -> new InventoryItem(
                        item.itemId(),
                        item.count(),
                        InventoryItemKind.BOXED
                ))
                        .toList();
                scopes.add(new Scope(
                        containerId + "/slot/" + slot.menuSlot(),
                        Optional.of(containerId),
                        boxedItems
                ));
            }
        }
        InventorySnapshot snapshot = new InventorySnapshot(
                sourceId,
                containerId,
                dimension,
                capturedAt,
                freshness,
                rootItems
        );
        return new InventoryCapture(snapshot, scopes);
    }

    public record Scope(
            String scopeId,
            Optional<String> parentScopeId,
            List<InventoryItem> items
    ) {
        public Scope {
            scopeId = requireText(scopeId, "scopeId");
            parentScopeId = Objects.requireNonNull(parentScopeId, "parentScopeId");
            parentScopeId = parentScopeId.map(
                    value -> requireText(value, "parentScopeId")
            );
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    public record SlotCapture(
            int menuSlot,
            String itemId,
            int count,
            InventoryItemKind kind,
            List<ItemCount> contents
    ) {
        public SlotCapture {
            if (menuSlot < 0) {
                throw new IllegalArgumentException("menuSlot must not be negative");
            }
            itemId = requireText(itemId, "itemId");
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
            }
            kind = Objects.requireNonNull(kind, "kind");
            contents = List.copyOf(Objects.requireNonNull(contents, "contents"));
            if (kind != InventoryItemKind.BOXED && !contents.isEmpty()) {
                throw new IllegalArgumentException("only boxed slots may have contents");
            }
        }
    }

    public record ItemCount(String itemId, int count) {
        public ItemCount {
            itemId = requireText(itemId, "itemId");
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
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
