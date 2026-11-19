package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.stowling.automation.ItemIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoragePackingPlannerTest {
    @Test
    void cachePartialBoxesAreCombinedWhileTheMixedLooseRefillSwitchIsRespected() {
        StoragePolicy boxes = new StoragePolicy(StorageMode.BOX_ONLY, 0, true, null);
        StoragePolicy mixedOff = new StoragePolicy(StorageMode.MIXED, 50, true, null, false);
        StoragePolicy mixedOn = new StoragePolicy(StorageMode.MIXED, 50, true, null, true);

        assertTrue(StoragePackingPlanner.unpackIncomingBox(false, false, boxes));
        assertTrue(StoragePackingPlanner.unpackIncomingBox(false, false, mixedOn));
        assertFalse(StoragePackingPlanner.unpackIncomingBox(false, false, mixedOff));
        assertFalse(StoragePackingPlanner.unpackIncomingBox(false, true, boxes));
        assertTrue(StoragePackingPlanner.unpackIncomingBox(true, true, mixedOff));
    }

    @Test
    void pureBoxModeStoresSmallRemaindersEvenForLegacyFullBoxOnlySettings() {
        var policy = new StoragePolicy(StorageMode.BOX_ONLY, 50, false, null);
        assertTrue(policy.allowPartialBoxes());
        for (int size : java.util.List.of(1, 16, 64)) {
            assertEquals(0, StoragePackingPlanner.looseLimit(policy, 54, size));
            assertTrue(StoragePackingPlanner.canPack(policy.allowPartialBoxes(), 1, 0, size));
            assertFalse(StoragePackingPlanner.canPack(policy.allowPartialBoxes(), 0, 0, size));
        }
        var legacy = new com.google.gson.Gson().fromJson(
                "{\"mode\":\"BOX_ONLY\",\"loosePercent\":50,\"allowPartialBoxes\":false}",
                StoragePolicy.class);
        assertTrue(legacy.allowPartialBoxes());
    }

    @Test
    void explicitSlotsUseExactChestSlotsAndKeepLegacyPercentages() {
        for (int size : java.util.List.of(1, 16, 64)) {
            for (int slots : java.util.List.of(0, 1, 19, 27, 54)) {
                var policy = new StoragePolicy(StorageMode.MIXED, 50, true, null,
                        true, slots, null);
                assertEquals(slots * size, StoragePackingPlanner.looseLimit(policy, 54, size));
                assertEquals(Math.min(27, slots) * size,
                        StoragePackingPlanner.looseLimit(policy, 27, size));
                assertEquals(slots * size,
                        StoragePackingPlanner.refillCount(policy, 54, size, 0, 0, 54 * size));
            }
        }
        var legacy = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        assertEquals(13 * 64, StoragePackingPlanner.looseLimit(legacy, 27, 64));
    }

    @Test
    void partialDestinationAndSlotsSurviveSerializationAndShifting() {
        String ref = "region:" + java.util.UUID.randomUUID();
        var policy = new StoragePolicy(StorageMode.MIXED, 35, true,
                new AreaBounds(0, 64, 0, 1, 64, 1), true, 19, ref);
        var gson = new com.google.gson.Gson();
        assertEquals(policy, gson.fromJson(gson.toJson(policy), StoragePolicy.class));
        assertEquals(19, policy.shift(5, 5).looseSlots());
        assertEquals(ref, policy.shift(5, 5).partialBoxDestinationRef());
        assertThrows(IllegalArgumentException.class,
                () -> new StoragePolicy(StorageMode.MIXED, 50, true, null, false, 55, null));
        assertThrows(IllegalArgumentException.class, () -> new StoragePolicy(StorageMode.MIXED,
                50, true, null, false, 19, "category-zone:" + java.util.UUID.randomUUID()));
    }

    @Test
    void bringsASpareForOverflowAfterToppingUpAnExistingBox() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        int boxSpace = 64;
        assertTrue(StoragePackingPlanner.shouldBringBox(mixed, 5 * 64, 0, 64, 0,
                boxSpace, 27 * 64));
        assertFalse(StoragePackingPlanner.shouldBringBox(mixed, 64, 0, 64, 0,
                boxSpace, 27 * 64));
    }

    @Test
    void fullBoxModePlansASpareUsingTheTargetsLooseStock() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        assertTrue(StoragePackingPlanner.shouldBringBox(mixed, 5 * 64, 0, 64, 0,
                64, 27 * 64));
        assertFalse(StoragePackingPlanner.shouldBringBox(mixed, 5 * 64, 0, 64, 0,
                64, 22 * 64));
        assertFalse(StoragePackingPlanner.shouldBringBox(mixed, 64, 0, 64, 0,
                64, 27 * 64));
    }

    @Test
    void fillsExistingBoxThenPacksTheRemainderWithOnlyTheRequiredLooseStock() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        int carried = 5 * 64;
        int loose = 27 * 64;
        int inside = 26 * 64;
        int borrowed = StoragePackingPlanner.targetItemsForPacking(mixed, carried, loose,
                27 * 64, inside, 64);
        assertEquals(0, borrowed);
        assertTrue(StoragePackingPlanner.canPack(false, carried + borrowed, inside, 64));
        carried -= 27 * 64 - inside;

        borrowed = StoragePackingPlanner.targetItemsForPacking(mixed, carried, loose,
                27 * 64, 0, 64);
        assertEquals(23 * 64, borrowed);
        assertTrue(StoragePackingPlanner.canPack(false, carried + borrowed, 0, 64));
        assertEquals(4 * 64, loose - borrowed);
    }

    @Test
    void insufficientStockDoesNotOverrideTheFullBoxRequirement() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        int borrowed = StoragePackingPlanner.targetItemsForPacking(mixed, 4 * 16, 22 * 16,
                27 * 16, 0, 16);
        assertEquals(0, borrowed);
        assertFalse(StoragePackingPlanner.canPack(false, 4 * 16 + borrowed, 0, 16));
        assertEquals(0, StoragePackingPlanner.targetItemsForPacking(mixed, 0, 27 * 16,
                27 * 16, 0, 16));
    }

    @Test
    void partialBoxModeKeepsTheExistingLooseReserve() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        assertEquals(0, StoragePackingPlanner.targetItemsForPacking(mixed, 64, 27 * 64,
                27 * 64, 0, 64));
        assertEquals(64, StoragePackingPlanner.targetItemsForPacking(mixed, 64, 28 * 64,
                27 * 64, 0, 64));
    }

    @Test
    void predictsOverflowUsingBothPolicyAndRecordedFreeSlots() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        long space = StoragePackingPlanner.looseSpace(mixed, 54, 64, 26 * 64, 0, 53, 0);
        assertEquals(64, space);
        assertFalse(StoragePackingPlanner.shouldBringBox(mixed, 64, space, 64, 0));
        assertTrue(StoragePackingPlanner.shouldBringBox(mixed, 65, space, 64, 0));
        assertEquals(0, StoragePackingPlanner.looseSpace(mixed, 54, 64, 0, 0, 54, 0));
        assertTrue(StoragePackingPlanner.shouldBringBox(mixed, 1, 0, 64, 0));
    }

    @Test
    void preparesAFullBoxOnlyWhenTheTripAndTargetCanFillIt() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        assertFalse(StoragePackingPlanner.shouldBringBox(mixed, 27 * 16, 27 * 16, 16, 0));
        assertFalse(StoragePackingPlanner.shouldBringBox(mixed, 26 * 16, 0, 16, 15));
        assertTrue(StoragePackingPlanner.shouldBringBox(mixed, 26 * 16, 0, 16, 16));
        assertTrue(StoragePackingPlanner.shouldBringBox(mixed, 27 * 16, 16, 16, 0));
    }

    @Test
    void mixedCapacityAndRemainderUseActualStackLimit() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        assertEquals(27 * 64, StoragePackingPlanner.looseLimit(mixed, 54, 64));
        assertEquals(13 * 16, StoragePackingPlanner.looseLimit(mixed, 27, 16));
        assertEquals(13, StoragePackingPlanner.looseLimit(mixed, 27, 1));
        assertTrue(StoragePackingPlanner.canPack(false, 64, 26 * 64, 64));
        assertFalse(StoragePackingPlanner.canPack(false, 63, 26 * 64, 64));
        assertTrue(StoragePackingPlanner.canPack(true, 1, 0, 16));
        assertFalse(StoragePackingPlanner.canPack(true, 0, 0, 64));
    }

    @Test
    void packingAreaMovesOutsideTheStorageBoundsAndKeepsPolicy() {
        StoragePolicy policy = new StoragePolicy(StorageMode.MIXED, 50, true,
                new AreaBounds(5, 64, 20, 6, 64, 20));
        StoragePolicy shifted = policy.shift(-3, 7);
        assertEquals(new AreaBounds(2, 64, 27, 3, 64, 27), shifted.packingArea());
        assertEquals(StorageMode.MIXED, shifted.mode());
        assertTrue(shifted.allowPartialBoxes());
        assertThrows(IllegalArgumentException.class,
                () -> new StoragePolicy(StorageMode.MIXED, 101, true, null));
    }

    @Test
    void sameColorBoxesKeepDistinctIdentityButClassifyByContents() {
        String stone = "minecraft:blue_shulker_box#first@minecraft:stone";
        String dirt = "minecraft:blue_shulker_box#second@minecraft:dirt";
        assertTrue(ItemIdentity.matches(stone, Set.of("minecraft:stone")));
        assertFalse(ItemIdentity.matches(dirt, Set.of("minecraft:stone")));
        assertEquals("minecraft:blue_shulker_box", ItemIdentity.base(stone));
        assertTrue(ItemIdentity.isBox(stone));
    }
}
