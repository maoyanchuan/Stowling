package dev.stowling.packing;

import static dev.stowling.packing.PackingTestFixtures.ITEM_ID;
import static dev.stowling.packing.PackingTestFixtures.workcell;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.PackingTrigger;
import org.junit.jupiter.api.Test;

class PackingThresholdTest {
    @Test
    void occupancyRatioTriggersAtConfiguredPercentage() {
        PackingThreshold threshold = new PackingThreshold(
                PackingThresholdMode.OCCUPIED_SLOT_RATIO,
                90
        );

        assertFalse(threshold.reached(PackingBoxContents.exact(ITEM_ID, 512, 8, 10)));
        assertTrue(threshold.reached(PackingBoxContents.exact(ITEM_ID, 576, 9, 10)));
    }

    @Test
    void itemCountUsesTotalItemsInsteadOfOccupiedSlots() {
        PackingThreshold threshold = new PackingThreshold(
                PackingThresholdMode.ITEM_COUNT,
                65
        );

        assertFalse(threshold.reached(PackingBoxContents.exact(ITEM_ID, 64, 2, 27)));
        assertTrue(threshold.reached(PackingBoxContents.exact(ITEM_ID, 65, 2, 27)));
    }

    @Test
    void mapsStationTriggerToThresholdMode() {
        PackingThreshold occupancy = workcell(
                "occupancy",
                PackingTrigger.ON_OCCUPANCY,
                75,
                PackingReleaseMode.AUTO_ON_CLOSE
        ).threshold();
        PackingThreshold itemCount = workcell(
                "count",
                PackingTrigger.ON_ITEM_COUNT,
                128,
                PackingReleaseMode.AUTO_ON_CLOSE
        ).threshold();

        assertTrue(occupancy.mode() == PackingThresholdMode.OCCUPIED_SLOT_RATIO);
        assertTrue(itemCount.mode() == PackingThresholdMode.ITEM_COUNT);
    }

    @Test
    void rejectsThresholdsOutsideModeRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PackingThreshold(PackingThresholdMode.OCCUPIED_SLOT_RATIO, 101)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PackingThreshold(PackingThresholdMode.ITEM_COUNT, 1729)
        );
    }
}
