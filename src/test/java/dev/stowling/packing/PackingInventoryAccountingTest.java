package dev.stowling.packing;

import static dev.stowling.packing.PackingTestFixtures.DIMENSION;
import static dev.stowling.packing.PackingTestFixtures.ITEM_ID;
import static dev.stowling.packing.PackingTestFixtures.OTHER_ITEM_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackingInventoryAccountingTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void separatesKnownLooseAndPackedCounts() {
        InventorySnapshot first = snapshot(
                "cache",
                InventoryFreshness.FRESH,
                item(ITEM_ID, 40, InventoryItemKind.LOOSE),
                item(ITEM_ID, 64, InventoryItemKind.BOXED)
        );
        InventorySnapshot second = snapshot(
                "bulk",
                InventoryFreshness.FRESH,
                item(ITEM_ID, 2, InventoryItemKind.LOOSE),
                item(OTHER_ITEM_ID, 9, InventoryItemKind.BOXED)
        );

        PackingInventorySummary summary = PackingInventoryAccounting.summarize(
                List.of(first, second)
        );

        assertEquals(42, summary.looseQuantity(ITEM_ID));
        assertEquals(64, summary.packedQuantity(ITEM_ID));
        assertEquals(0, summary.looseQuantity(OTHER_ITEM_ID));
        assertEquals(9, summary.packedQuantity(OTHER_ITEM_ID));
        assertFalse(summary.hasUnknownContents());
    }

    @Test
    void unknownItemKindNeverBecomesAnExactPackedTotal() {
        InventorySnapshot snapshot = snapshot(
                "unknown-box",
                InventoryFreshness.FRESH,
                item(ITEM_ID, 64, InventoryItemKind.UNKNOWN)
        );

        PackingInventorySummary summary = PackingInventoryAccounting.summarize(
                List.of(snapshot)
        );

        assertEquals(0, summary.looseQuantity(ITEM_ID));
        assertEquals(0, summary.packedQuantity(ITEM_ID));
        assertEquals(
                UnknownInventoryReason.ITEM_KIND_UNKNOWN,
                summary.unknownContents().getFirst().reason()
        );
        assertEquals(64, summary.unknownContents().getFirst().reportedCount());
    }

    @Test
    void staleSnapshotIsReportedAsUnknownInsteadOfCurrentStock() {
        InventorySnapshot stale = snapshot(
                "old-cache",
                InventoryFreshness.STALE,
                item(ITEM_ID, 32, InventoryItemKind.LOOSE)
        );

        PackingInventorySummary summary = PackingInventoryAccounting.summarize(
                List.of(stale)
        );

        assertEquals(0, summary.looseQuantity(ITEM_ID));
        assertTrue(summary.hasUnknownContents());
        assertEquals(
                UnknownInventoryReason.SNAPSHOT_STALE,
                summary.unknownContents().getFirst().reason()
        );
    }

    @Test
    void unknownFreshnessIsAlsoKeptOutOfExactTotals() {
        InventorySnapshot snapshot = snapshot(
                "uncertain",
                InventoryFreshness.UNKNOWN,
                item(ITEM_ID, 64, InventoryItemKind.BOXED)
        );

        PackingInventorySummary summary = PackingInventoryAccounting.summarize(
                List.of(snapshot)
        );

        assertEquals(0, summary.packedQuantity(ITEM_ID));
        assertEquals(
                UnknownInventoryReason.SNAPSHOT_FRESHNESS_UNKNOWN,
                summary.unknownContents().getFirst().reason()
        );
    }

    private static InventorySnapshot snapshot(
            String containerId,
            InventoryFreshness freshness,
            InventoryItem... items
    ) {
        return new InventorySnapshot(
                "ui",
                containerId,
                DIMENSION,
                CAPTURED_AT,
                freshness,
                List.of(items)
        );
    }

    private static InventoryItem item(
            String itemId,
            int count,
            InventoryItemKind kind
    ) {
        return new InventoryItem(itemId, count, kind);
    }
}
