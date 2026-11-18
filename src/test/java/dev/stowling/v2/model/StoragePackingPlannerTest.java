package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.stowling.automation.ItemIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoragePackingPlannerTest {
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
