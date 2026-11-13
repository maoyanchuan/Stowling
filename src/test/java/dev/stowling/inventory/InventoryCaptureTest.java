package dev.stowling.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryCaptureTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-13T00:00:00Z");

    @Test
    void mapsNonContiguousContainerSlotsWithoutChangingRealMenuIndexes() {
        MenuSlotClassification classification = MenuSlotClassification.classify(
                List.of(true, false, true, true, false, true)
        );

        assertEquals(List.of(1, 4), classification.containerSlots());
        assertEquals(List.of(0, 2, 3, 5), classification.playerSlots());
        assertEquals(2, classification.containerCapacity());
        assertEquals(List.of(
                new MenuSlotClassification.ContainerSlotMapping(0, 1),
                new MenuSlotClassification.ContainerSlotMapping(1, 4)
        ), classification.logicalContainerSlots());
    }

    @Test
    void capturesLooseItemsAndNestedBoxContents() {
        InventoryCapture capture = capture(List.of(
                slot(0, "minecraft:stone", 12, InventoryItemKind.LOOSE, List.of()),
                slot(
                        7,
                        "minecraft:shulker_box",
                        1,
                        InventoryItemKind.BOXED,
                        List.of(
                                new InventoryCapture.ItemCount("minecraft:iron_ingot", 32),
                                new InventoryCapture.ItemCount("minecraft:redstone", 5)
                        )
                )
        ));

        assertEquals(List.of(
                new InventoryItem("minecraft:stone", 12, InventoryItemKind.LOOSE),
                new InventoryItem("minecraft:shulker_box", 1, InventoryItemKind.BOXED)
        ), capture.snapshot().items());
        InventoryCapture.Scope scope = capture.nestedScopes().getFirst();
        assertEquals("cache/slot/7", scope.scopeId());
        assertEquals("cache", scope.parentScopeId().orElseThrow());
        assertEquals(List.of(
                new InventoryItem("minecraft:iron_ingot", 32, InventoryItemKind.BOXED),
                new InventoryItem("minecraft:redstone", 5, InventoryItemKind.BOXED)
        ), scope.items());
    }

    @Test
    void capturesEmptyBoxWithAnEmptyScope() {
        InventoryCapture capture = capture(List.of(slot(
                3,
                "minecraft:blue_shulker_box",
                1,
                InventoryItemKind.BOXED,
                List.of()
        )));

        assertEquals(InventoryItemKind.BOXED, capture.snapshot().items().getFirst().kind());
        assertEquals(1, capture.nestedScopes().size());
        assertTrue(capture.nestedScopes().getFirst().items().isEmpty());
    }

    @Test
    void keepsUndecodableBoxUnknownWithoutAnExactScope() {
        InventoryCapture capture = capture(List.of(slot(
                4,
                "minecraft:red_shulker_box",
                1,
                InventoryItemKind.UNKNOWN,
                List.of()
        )));

        assertEquals(InventoryItemKind.UNKNOWN, capture.snapshot().items().getFirst().kind());
        assertTrue(capture.nestedScopes().isEmpty());
    }

    private static InventoryCapture capture(List<InventoryCapture.SlotCapture> slots) {
        return InventoryCapture.fromSlots(
                "test",
                "cache",
                "minecraft:overworld",
                CAPTURED_AT,
                InventoryFreshness.FRESH,
                slots
        );
    }

    private static InventoryCapture.SlotCapture slot(
            int menuSlot,
            String itemId,
            int count,
            InventoryItemKind kind,
            List<InventoryCapture.ItemCount> contents
    ) {
        return new InventoryCapture.SlotCapture(
                menuSlot,
                itemId,
                count,
                kind,
                contents
        );
    }
}
