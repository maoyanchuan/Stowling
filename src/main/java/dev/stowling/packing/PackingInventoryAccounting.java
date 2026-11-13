package dev.stowling.packing;

import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

public final class PackingInventoryAccounting {
    private PackingInventoryAccounting() {
    }

    public static PackingInventorySummary summarize(List<InventorySnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        TreeMap<String, Long> looseItems = new TreeMap<>();
        TreeMap<String, Long> packedItems = new TreeMap<>();
        List<UnknownInventoryContent> unknownContents = new ArrayList<>();

        for (InventorySnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "snapshot");
            for (InventoryItem item : snapshot.items()) {
                UnknownInventoryReason reason = unknownReason(snapshot, item);
                if (reason != null) {
                    unknownContents.add(new UnknownInventoryContent(
                            snapshot.sourceId(),
                            snapshot.containerId(),
                            item.itemId(),
                            item.count(),
                            reason
                    ));
                    continue;
                }
                TreeMap<String, Long> totals = item.kind() == InventoryItemKind.LOOSE
                        ? looseItems
                        : packedItems;
                totals.merge(item.itemId(), (long) item.count(), Math::addExact);
            }
        }
        return new PackingInventorySummary(looseItems, packedItems, unknownContents);
    }

    private static UnknownInventoryReason unknownReason(
            InventorySnapshot snapshot,
            InventoryItem item
    ) {
        if (snapshot.freshness() == InventoryFreshness.STALE) {
            return UnknownInventoryReason.SNAPSHOT_STALE;
        }
        if (snapshot.freshness() == InventoryFreshness.UNKNOWN) {
            return UnknownInventoryReason.SNAPSHOT_FRESHNESS_UNKNOWN;
        }
        if (item.kind() == InventoryItemKind.UNKNOWN) {
            return UnknownInventoryReason.ITEM_KIND_UNKNOWN;
        }
        return null;
    }
}
