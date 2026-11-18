package dev.stowling.inventory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StoragePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordedStorageCapacityTest {
    @Test
    void ranksMatchingSlackBeforeUnknownAndFullWithoutFilteringAnyOut() {
        String item = "minecraft:stone";
        Map<String, Integer> incoming = Map.of(item, 10);
        var canFit = new WarehouseInventory.Entry(item, item, "Stone", 50, 0, 64);
        var noRoom = new WarehouseInventory.Entry(item, item, "Stone", 64, 0, 64);

        int fits = RecordedStorageCapacity.rank(
                stock(canFit), StoragePolicy.DEFAULT, incoming, Map.of());
        int unknown = RecordedStorageCapacity.rank(
                null, StoragePolicy.DEFAULT, incoming, Map.of());
        int full = RecordedStorageCapacity.rank(
                stock(noRoom), StoragePolicy.DEFAULT, incoming, Map.of());

        assertTrue(fits < unknown);
        assertTrue(unknown < full);
    }

    @Test
    void ranksAnEmptyRecordedChestUsingTheCarriedStackSize() {
        String item = "minecraft:stone";
        var empty = new WarehouseInventory.Container("empty", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(1, 64, 0)), Instant.ofEpochSecond(1),
                27, 0, 0, 0, 0, List.of());
        Map<String, Integer> incoming = Map.of(item, 64);
        Map<String, Integer> stackSizes = Map.of(item, 64);
        assertTrue(RecordedStorageCapacity.rank(empty, StoragePolicy.DEFAULT, incoming, stackSizes)
                < RecordedStorageCapacity.rank(null, StoragePolicy.DEFAULT, incoming, stackSizes));
    }

    private static WarehouseInventory.Container stock(WarehouseInventory.Entry entry) {
        return new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(68, -35, 29), new BlockPosition(68, -35, 30)),
                Instant.ofEpochSecond(1), 54, 54, 0, 0, 0, List.of(entry));
    }
}
