package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class LooseRefillPlannerTest {
    @Test
    void oldPoliciesAndTheDefaultLeaveRefillingDisabled() {
        StoragePolicy legacy = new Gson().fromJson("""
                {"mode":"MIXED","loosePercent":50,"allowPartialBoxes":false}
                """, StoragePolicy.class);
        assertFalse(legacy.refillLoose());
        assertFalse(StoragePolicy.DEFAULT.refillLoose());
        assertEquals(0, refill(legacy, 54, 64, 0, 0, 27 * 64));
    }

    @Test
    void restoresOnlyTheDeficitEvenWhenOnlyFullBoxesMayBePacked() {
        StoragePolicy policy = policy(StorageMode.MIXED, true);
        assertFalse(policy.allowPartialBoxes());
        assertEquals(2 * 64, refill(policy, 54, 64, 25 * 64, 0, 27 * 64));
        assertEquals(0, refill(policy, 54, 64, 27 * 64, 0, 27 * 64));
        assertEquals(0, refill(policy, 54, 64, 28 * 64, 0, 27 * 64));
    }

    @Test
    void stopsAtAvailableBoxStockAndReplansAfterPlayersChangeTheChest() {
        StoragePolicy policy = policy(StorageMode.MIXED, true);
        assertEquals(64, refill(policy, 54, 64, 25 * 64, 0, 64));
        assertEquals(3, refill(policy, 54, 64, 27 * 64 - 3, 0, 64));
        assertEquals(0, refill(policy, 54, 64, 27 * 64 + 1, 0, 64));
        assertEquals(0, refill(policy, 54, 64, 0, 0, 0));
    }

    @Test
    void accountsForOtherLooseSlotsAndTheActualItemStackSize() {
        StoragePolicy policy = policy(StorageMode.MIXED, true);
        assertEquals(16, refill(policy, 27, 16, 10 * 16, 2, 27 * 16));
        assertEquals(1, refill(policy, 27, 1, 10, 2, 27));
        assertEquals(0, refill(policy, 27, 16, 0, 14, 27 * 16));
    }

    @Test
    void normalAndBoxOnlyModesDoNotUnpackTheirBoxes() {
        assertEquals(0, refill(policy(StorageMode.NORMAL, true), 54, 64, 0, 0, 1728));
        assertEquals(0, refill(policy(StorageMode.BOX_ONLY, true), 54, 64, 0, 0, 1728));
        assertEquals(0, refill(policy(StorageMode.MIXED, false), 54, 64, 0, 0, 1728));
    }

    @Test
    void savesAndShiftsTheSwitchAlongWithThePackingArea() {
        StoragePolicy policy = new StoragePolicy(StorageMode.MIXED, 50, false,
                new AreaBounds(1, 64, 1, 2, 64, 2), true);
        StoragePolicy saved = new Gson().fromJson(new Gson().toJson(policy), StoragePolicy.class);
        assertEquals(policy, saved);
        assertTrue(saved.shift(2, -1).refillLoose());
        assertEquals(new AreaBounds(3, 64, 0, 4, 64, 1), saved.shift(2, -1).packingArea());
    }

    private static StoragePolicy policy(StorageMode mode, boolean enabled) {
        return new StoragePolicy(mode, 50, false, null, enabled);
    }

    private static int refill(StoragePolicy policy, int slots, int stackSize, int loose,
            int otherSlots, int available) {
        return StoragePackingPlanner.refillCount(policy, slots, stackSize, loose,
                otherSlots, available);
    }
}
