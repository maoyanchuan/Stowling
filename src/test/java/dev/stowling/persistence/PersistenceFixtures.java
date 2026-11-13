package dev.stowling.persistence;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PersistenceFixtures {
    static final Instant CAPTURED_AT = Instant.parse("2026-11-13T12:00:00Z");
    static final Instant OBSERVED_AT = Instant.parse("2026-11-13T12:00:05Z");

    private PersistenceFixtures() {
    }

    static ContainerRef container(String id) {
        return new ContainerRef(
                id,
                "minecraft:overworld",
                new BlockPosition(10, 64, -5),
                ContainerType.CHEST
        );
    }

    static InventoryItem item(String itemId, int count, InventoryItemKind kind) {
        return new InventoryItem(itemId, count, kind);
    }

    static InventorySnapshot snapshot(
            ContainerRef container,
            InventoryFreshness freshness,
            List<InventoryItem> items,
            Instant capturedAt
    ) {
        return new InventorySnapshot(
                "screen",
                container.id(),
                container.dimension(),
                capturedAt,
                freshness,
                items
        );
    }

    static TaskSnapshot task(
            UUID id,
            TaskStatus status,
            AutomationState state,
            Optional<String> failureReason
    ) {
        return new TaskSnapshot(
                id,
                TaskKind.PICKUP,
                status,
                state,
                CAPTURED_AT,
                OBSERVED_AT,
                failureReason
        );
    }
}
