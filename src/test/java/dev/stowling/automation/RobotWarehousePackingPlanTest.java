package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.RobotWarehousePackingPlan.Box;
import dev.stowling.automation.ShulkerTransferPlanner.Slot;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RobotWarehousePackingPlanTest {
    @Test
    void totalOverflowFillsExistingStockBeforeUsingAnEmptyBoxEvenWhenItAcceptsLess() {
        var empty = new Box("empty", List.of());
        var partial = new Box("partial",
                Collections.nCopies(27, new Slot("minecraft:stone", 63, 64)));
        var incoming = Map.of("minecraft:stone", 64);
        var sizes = Map.of("minecraft:stone", 64);
        assertEquals("empty", RobotWarehousePackingPlan.select(List.of(empty, partial),
                incoming, sizes, true).orElseThrow());
        assertEquals("partial", RobotWarehousePackingPlan.select(List.of(empty, partial),
                incoming, sizes, true, true).orElseThrow());
        assertEquals(Map.of("minecraft:stone", 27), RobotWarehousePackingPlan.fitting(
                partial.slots(), 27, incoming, sizes, true));
        assertTrue(RobotWarehousePackingPlan.select(List.of(empty),
                incoming, sizes, true, true).isEmpty());
        var full = new Box("full", Collections.nCopies(27, new Slot("minecraft:stone", 64, 64)));
        assertTrue(RobotWarehousePackingPlan.select(List.of(full, empty),
                incoming, sizes, true, true).isEmpty());
    }

    @Test
    void cleanupKeepsMixedBoxesIntactOnlyWhenTheWarehouseAllowsThem() {
        var contents = List.of(new Slot("minecraft:stone", 64, 64),
                new Slot("minecraft:dirt", 32, 64));

        assertFalse(RobotWarehousePackingPlan.acceptsIntact(contents, false));
        assertTrue(RobotWarehousePackingPlan.acceptsIntact(contents, true));
    }

    @Test
    void cleanupCanStorePurePartialBoxesWithDifferentComponentsWithoutUnpackingThem() {
        var contents = List.of(new Slot("minecraft:stone#variant-a", 16, 64),
                new Slot("minecraft:stone#variant-b", 8, 64));

        assertTrue(RobotWarehousePackingPlan.acceptsIntact(contents, false));
        assertTrue(RobotWarehousePackingPlan.acceptsIntact(List.of(), false));
    }

    @Test
    void pureModeOnlyReusesABoxWithMatchingContents() {
        var mixed = new Box("mixed", List.of(new Slot("minecraft:stone", 64, 64),
                new Slot("minecraft:dirt", 64, 64)));
        var stone = new Box("stone", List.of(new Slot("minecraft:stone", 64, 64)));
        assertEquals("stone", RobotWarehousePackingPlan.select(List.of(mixed, stone),
                Map.of("minecraft:stone", 64, "minecraft:dirt", 128), Map.of(), false)
                .orElseThrow());
        assertTrue(RobotWarehousePackingPlan.select(List.of(mixed),
                Map.of("minecraft:stone", 64), Map.of(), false).isEmpty());
    }

    @Test
    void pureModeUsesOnlyOneCategoryWhenFillingAnEmptyBox() {
        var incoming = Map.of("minecraft:stone", 64, "minecraft:dirt", 128);
        assertEquals(Map.of("minecraft:dirt", 128), RobotWarehousePackingPlan.fitting(
                List.of(), 27, incoming, Map.of(), false));
        assertEquals(incoming, RobotWarehousePackingPlan.fitting(
                List.of(), 27, incoming, Map.of(), true));
    }

    @Test
    void pureModePreservesExactVariantKeysAndNeverAcceptsNestedBoxes() {
        String first = "minecraft:stone#variant-a";
        String second = "minecraft:stone#variant-b";
        var incoming = Map.of(first, 20, second, 30, "minecraft:dirt", 10);
        assertEquals(Map.of(first, 20, second, 30), RobotWarehousePackingPlan.fitting(
                List.of(new Slot(first, 64, 64)), 27, incoming, Map.of(), false));
        assertTrue(RobotWarehousePackingPlan.fitting(List.of(), 27,
                Map.of("minecraft:shulker_box#packed@minecraft:stone", 1), Map.of(), false)
                .isEmpty());
    }

    @Test
    void unrelatedMixedContentsCanReceiveANewOverflowCategoryWithoutAMarker() {
        var mixed = new Box("mixed", List.of(new Slot("minecraft:stone", 64, 64),
                new Slot("minecraft:dirt", 64, 64)));

        assertEquals("mixed", RobotWarehousePackingPlan.select(List.of(mixed),
                Map.of("minecraft:gold_ingot", 128), Map.of("minecraft:gold_ingot", 64))
                .orElseThrow());
    }

    @Test
    void choosesTheBoxThatCanAcceptMoreOfTheCurrentOverflow() {
        var nearlyFull = new Box("nearly-full",
                Collections.nCopies(27, new Slot("minecraft:stone", 63, 64)));
        var freeSlot = new Box("free-slot",
                Collections.nCopies(26, new Slot("minecraft:dirt", 64, 64)));

        assertEquals("free-slot", RobotWarehousePackingPlan.select(List.of(nearlyFull, freeSlot),
                Map.of("minecraft:stone", 64), Map.of("minecraft:stone", 64)).orElseThrow());
    }

    @Test
    void fillsExistingStockBeforeAnEmptyBoxWhenBothCanAcceptTheWholeBatch() {
        var empty = new Box("empty", List.of());
        var mixed = new Box("mixed", List.of(new Slot("minecraft:stone", 64, 64),
                new Slot("minecraft:dirt", 64, 64)));

        assertEquals("mixed", RobotWarehousePackingPlan.select(List.of(empty, mixed),
                Map.of("minecraft:gold_ingot", 64), Map.of("minecraft:gold_ingot", 64))
                .orElseThrow());
    }

    @Test
    void exactComponentsDetermineWhetherOccupiedSlotsCanBeToppedUp() {
        String variant = "minecraft:stone#variant-a";
        var box = new Box("occupied", Collections.nCopies(27, new Slot(variant, 63, 64)));

        assertEquals("occupied", RobotWarehousePackingPlan.select(List.of(box),
                Map.of(variant, 27), Map.of(variant, 64)).orElseThrow());
        assertTrue(RobotWarehousePackingPlan.select(List.of(box),
                Map.of("minecraft:stone#variant-b", 1), Map.of()).isEmpty());
    }

    @Test
    void realStackLimitsDecideBetweenSlotsAndStackSlack() {
        var oneFreeSlot = new Box("one-slot",
                Collections.nCopies(26, new Slot("minecraft:dirt", 64, 64)));
        var pearlSlack = new Box("pearl-slack",
                Collections.nCopies(27, new Slot("minecraft:ender_pearl", 15, 16)));

        assertEquals("pearl-slack", RobotWarehousePackingPlan.select(
                List.of(oneFreeSlot, pearlSlack), Map.of("minecraft:ender_pearl", 27),
                Map.of("minecraft:ender_pearl", 16)).orElseThrow());
    }

    @Test
    void requestsANewBoxWhenNoExistingBoxFitsAndNeverNestsBoxes() {
        var full = new Box("full", Collections.nCopies(27, new Slot("minecraft:stone", 64, 64)));
        assertTrue(RobotWarehousePackingPlan.select(List.of(full),
                Map.of("minecraft:stone", 1), Map.of()).isEmpty());
        assertTrue(RobotWarehousePackingPlan.select(List.of(),
                Map.of("minecraft:stone", 1), Map.of()).isEmpty());
        assertTrue(RobotWarehousePackingPlan.select(List.of(new Box("empty", List.of())),
                Map.of("minecraft:shulker_box#packed@minecraft:stone", 1), Map.of()).isEmpty());
    }
}
