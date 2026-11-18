package dev.stowling.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordedContainerCapacityTest {
    @Test
    void chestCapacityDoesNotShrinkToTheNumberOfRecordedStacks() {
        assertEquals(27, RecordedContainerCapacity.slots("minecraft:chest", 1));
        assertEquals(54, RecordedContainerCapacity.slots("minecraft:chest", 2));
        assertEquals(54, RecordedContainerCapacity.slots("minecraft:trapped_chest", 2));
    }

    @Test
    void recognizesTheOtherSupportedSortingContainers() {
        assertEquals(27, RecordedContainerCapacity.slots("minecraft:barrel", 1));
        assertEquals(27, RecordedContainerCapacity.slots("minecraft:shulker_box", 1));
        assertEquals(27, RecordedContainerCapacity.slots("minecraft:blue_shulker_box", 1));
        assertEquals(5, RecordedContainerCapacity.slots("minecraft:hopper", 1));
        assertEquals(9, RecordedContainerCapacity.slots("minecraft:dropper", 1));
    }

    @Test
    void emptyAndLightlyFilledChestsDoNotFetchBoxesForEachKind() {
        String item = "minecraft:birch_leaves";
        StoragePolicy policy = new StoragePolicy(StorageMode.MIXED, 50, true, null, true);
        int slots = RecordedContainerCapacity.slots("minecraft:chest", 2);
        var empty = stock(slots, List.of());
        var oneStack = stock(slots,
                List.of(new WarehouseInventory.Entry(item, item, "Leaves", 64, 0, 64)));
        assertFalse(RecordedStorageCapacity.shouldReserveBox(empty, policy,
                Map.of(item, 64), Map.of(item, 64)));
        assertFalse(RecordedStorageCapacity.shouldReserveBox(oneStack, policy,
                Map.of(item, 64), Map.of(item, 64)));
        assertTrue(RecordedStorageCapacity.shouldReserveBox(oneStack, policy,
                Map.of(item, 27 * 64), Map.of(item, 64)));
    }

    @Test
    void unknownContainerCapacityDoesNotMasqueradeAsAFullChest() {
        assertEquals(0, RecordedContainerCapacity.slots("example:custom_storage", 1));
        assertEquals(0, RecordedContainerCapacity.slots("minecraft:chest", 0));
        var stock = stock(0, List.of());
        var policy = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        Map<String, Integer> incoming = Map.of("minecraft:stone", 64);
        assertFalse(RecordedStorageCapacity.shouldReserveBox(stock, policy, incoming, incoming));
        assertEquals(1, RecordedStorageCapacity.rank(stock, policy, incoming, incoming));
    }

    private static WarehouseInventory.Container stock(int slots,
            List<WarehouseInventory.Entry> items) {
        return new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(0, 64, 0), new BlockPosition(1, 64, 0)),
                Instant.ofEpochSecond(1), slots, items.size(), 0, 0, 0, items);
    }
}
