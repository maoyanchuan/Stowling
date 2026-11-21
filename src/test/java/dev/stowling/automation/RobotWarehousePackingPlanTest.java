package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.RobotWarehousePackingPlan.Box;
import dev.stowling.automation.ShulkerTransferPlanner.Slot;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RobotWarehousePackingPlanTest {
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
