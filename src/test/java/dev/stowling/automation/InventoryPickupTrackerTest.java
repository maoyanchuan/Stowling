package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InventoryPickupTrackerTest {
    @Test
    void receiptsCountNonStackablePickupsEvenWhenInventoryTotalsStayConstant() {
        Object world = new Object();
        InventoryPickupTracker tracker = new InventoryPickupTracker();
        String disc = "minecraft:music_disc_otherside";
        long before = tracker.count(world, disc);

        for (int slot = 0; slot < 8; slot++) tracker.record(world, disc, 1);

        assertEquals(8, tracker.count(world, disc) - before);
        assertEquals(0, tracker.count(world, "minecraft:music_disc_cat"));
    }

    @Test
    void exactComponentsAndWorldSessionsHaveIndependentReceipts() {
        Object firstWorld = new Object();
        InventoryPickupTracker tracker = new InventoryPickupTracker();
        tracker.record(firstWorld, "minecraft:stone#first", 3);
        tracker.record(firstWorld, "minecraft:stone#second", 5);

        assertEquals(3, tracker.count(firstWorld, "minecraft:stone#first"));
        assertEquals(5, tracker.count(firstWorld, "minecraft:stone#second"));
        assertEquals(0, tracker.count(new Object(), "minecraft:stone#first"));
    }
}
