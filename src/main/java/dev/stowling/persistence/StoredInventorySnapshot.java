package dev.stowling.persistence;

import dev.stowling.inventory.InventorySnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record StoredInventorySnapshot(
        InventorySnapshot snapshot,
        Instant observedAt,
        List<NestedInventoryScope> nestedScopes
) {
    public StoredInventorySnapshot {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        nestedScopes = List.copyOf(Objects.requireNonNull(nestedScopes, "nestedScopes"));
        if (observedAt.isBefore(snapshot.capturedAt())) {
            throw new IllegalArgumentException("observedAt must not precede capturedAt");
        }
    }
}
