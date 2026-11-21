package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ShulkerTransferPlanner.Slot;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassificationOverflowCapacityTest {
    @Test
    void ordinaryStorageChecksExactStackSpaceAndTreatsUnreadStockAsUnknown() {
        String stone = "minecraft:stone";
        var stock = List.of(new Slot(stone, 63, 64));
        assertTrue(ClassificationOverflowCapacity.hasSpace(StoragePolicy.DEFAULT, stock,
                1, stone, 64, false));
        assertFalse(ClassificationOverflowCapacity.hasSpace(StoragePolicy.DEFAULT, stock,
                1, stone + "#variant", 64, false));
        assertTrue(ClassificationOverflowCapacity.hasSpace(StoragePolicy.DEFAULT, List.of(),
                0, stone, 64, false));
    }

    @Test
    void boxedStorageStillHasSpaceWhenItCanTopUpAnExistingBox() {
        var policy = new StoragePolicy(StorageMode.BOX_ONLY, 0, true, null);
        var stock = List.of(new Slot("minecraft:shulker_box#partial", 1, 1));
        String sword = "minecraft:iron_sword";
        assertTrue(ClassificationOverflowCapacity.hasSpace(policy, stock, 1, sword, 1, true));
        assertFalse(ClassificationOverflowCapacity.hasSpace(policy, stock, 1, sword, 1, false));
        assertTrue(ClassificationOverflowCapacity.hasSpace(policy, stock, 2, sword, 1, false));
    }
}
