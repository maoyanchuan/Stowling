package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShulkerTransferPlannerTest {
    @Test
    void threeSingleStackBoxesShareOneBatchInsteadOfResettingCapacityForEveryBox() {
        Map<String, Integer> carried = new LinkedHashMap<>();
        carried.put("minecraft:shulker_box", 1);
        Map<String, Integer> sizes = Map.of("minecraft:stone", 64);
        for (int source = 0; source < 3; source++) {
            assertEquals(27 - source,
                    ShulkerTransferPlanner.remainingBatchSlots(carried, sizes, 27));
            carried.merge("minecraft:stone", 64, Math::addExact);
        }

        assertEquals(192, carried.get("minecraft:stone"));
        assertEquals(24, ShulkerTransferPlanner.remainingBatchSlots(carried, sizes, 27));
        Map<String, Integer> packed = ShulkerTransferPlanner.fitting(List.of(), 27,
                Map.of("minecraft:stone", 192), sizes);
        assertEquals(Map.of("minecraft:stone", 192), packed);
    }

    @Test
    void reusesMatchingSlackEvenWhenAllPhysicalSlotsAreOccupied() {
        List<ShulkerTransferPlanner.Slot> contents = new ArrayList<>();
        contents.add(new ShulkerTransferPlanner.Slot("minecraft:stone#same", 32, 64));
        for (int index = 1; index < 27; index++) {
            contents.add(new ShulkerTransferPlanner.Slot("minecraft:dirt", 64, 64));
        }

        assertEquals(Map.of("minecraft:stone#same", 32), ShulkerTransferPlanner.fitting(
                contents, 27, Map.of("minecraft:stone#same", 64),
                Map.of("minecraft:stone#same", 64)));
        assertTrue(ShulkerTransferPlanner.fitting(contents, 27,
                Map.of("minecraft:stone#different", 64),
                Map.of("minecraft:stone#different", 64)).isEmpty());
    }

    @Test
    void partialOverflowBoxAcceptsMoreMatchingItemsAndOtherItemsInItsEmptySlots() {
        List<ShulkerTransferPlanner.Slot> contents = List.of(
                new ShulkerTransferPlanner.Slot("minecraft:stone", 32, 64));
        Map<String, Integer> incoming = new LinkedHashMap<>();
        incoming.put("minecraft:stone", 96);
        incoming.put("minecraft:ender_pearl", 20);

        assertEquals(incoming, ShulkerTransferPlanner.fitting(contents, 27, incoming,
                Map.of("minecraft:stone", 64, "minecraft:ender_pearl", 16)));
    }

    @Test
    void physicalPartialStacksAreCountedIndividuallyAndNeverAssumedToBeCompacted() {
        List<ShulkerTransferPlanner.Slot> contents = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            contents.add(new ShulkerTransferPlanner.Slot("minecraft:stone", 32, 64));
        }
        assertTrue(ShulkerTransferPlanner.fitting(contents, 27,
                Map.of("minecraft:dirt", 64), Map.of("minecraft:dirt", 64)).isEmpty());
        assertEquals(Map.of("minecraft:stone", 864), ShulkerTransferPlanner.fitting(contents, 27,
                Map.of("minecraft:stone", 1000), Map.of("minecraft:stone", 64)));
    }

    @Test
    void usesActualStackLimitsAndDoesNotNestShulkerBoxes() {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        incoming.put("minecraft:ender_pearl", 40);
        incoming.put("minecraft:diamond_pickaxe#tool", 2);
        incoming.put("minecraft:shulker_box#partial@minecraft:stone", 1);

        assertEquals(Map.of("minecraft:ender_pearl", 32), ShulkerTransferPlanner.fitting(
                List.of(), 2, incoming,
                Map.of("minecraft:ender_pearl", 16, "minecraft:diamond_pickaxe#tool", 1)));
        assertEquals(21, ShulkerTransferPlanner.remainingBatchSlots(incoming,
                Map.of("minecraft:ender_pearl", 16, "minecraft:diamond_pickaxe#tool", 1), 27));
    }

    @Test
    void fullBoxesHaveNoCapacityAndItemVariantsUseSeparateBatchSlots() {
        List<ShulkerTransferPlanner.Slot> contents = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            contents.add(new ShulkerTransferPlanner.Slot("minecraft:stone", 64, 64));
        }
        assertTrue(ShulkerTransferPlanner.fitting(contents, 27,
                Map.of("minecraft:stone", 1), Map.of("minecraft:stone", 64)).isEmpty());
        assertEquals(25, ShulkerTransferPlanner.remainingBatchSlots(Map.of(
                "minecraft:stone#first", 32, "minecraft:stone#second", 32), Map.of(), 27));
    }
}
