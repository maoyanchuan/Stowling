package dev.stowling.packing;

import java.util.Objects;

public record UnknownInventoryContent(
        String sourceId,
        String containerId,
        String itemId,
        int reportedCount,
        UnknownInventoryReason reason
) {
    public UnknownInventoryContent {
        sourceId = PackingChecks.requireText(sourceId, "sourceId");
        containerId = PackingChecks.requireText(containerId, "containerId");
        itemId = PackingChecks.requireText(itemId, "itemId");
        if (reportedCount < 0) {
            throw new IllegalArgumentException("reportedCount must not be negative");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }
}
