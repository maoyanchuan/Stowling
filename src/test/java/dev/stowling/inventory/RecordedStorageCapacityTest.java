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
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecordedStorageCapacityTest {
    @Test
    void partialBoxesAtTheSelectedLocationAreReservedBeforeEmptySupply() {
        String item = "minecraft:birch_leaves";
        var inside = new WarehouseInventory.Entry(item, item, "Inside", 26 * 64, 0, 64);
        var loose = new WarehouseInventory.Entry(item, item, "Loose", 27 * 64, 0, 64);
        var stock = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(1, 64, 0)), Instant.ofEpochSecond(1),
                54, 27, 0, 0, 0, List.of(loose));
        var partials = List.of(new WarehouseInventory.Box(item, 26, List.of(inside)));
        var policy = new StoragePolicy(StorageMode.MIXED, 50, true, null, false, 27,
                "coordinate:" + java.util.UUID.randomUUID());
        assertFalse(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                Map.of(item, 64), Map.of(item, 64), partials));
        assertTrue(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                Map.of(item, 65), Map.of(item, 64), partials));
    }

    @Test
    void existingPartialBoxAbsorbsIncomingBeforeReservingAnotherBox() {
        String item = "minecraft:birch_leaves";
        for (int size : List.of(64, 16)) {
            var inside = new WarehouseInventory.Entry(item, item, "Inside", 26L * size, 0, size);
            var loose = new WarehouseInventory.Entry(item, item, "Loose", 27L * size, 0, size);
            var partial = new WarehouseInventory.Box(item, 26, List.of(inside));
            var stock = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                    List.of(new BlockPosition(1, 64, 0)), Instant.ofEpochSecond(1),
                    54, 28, 0, 1, 0, List.of(loose), List.of(partial));
            for (boolean allowPartial : List.of(false, true)) {
                var policy = new StoragePolicy(StorageMode.MIXED, 50, allowPartial, null);
                assertFalse(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                        Map.of(item, size - 1), Map.of(item, size)));
                assertFalse(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                        Map.of(item, size), Map.of(item, size)));
                assertTrue(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                        Map.of(item, size + 1), Map.of(item, size)));
            }
        }
    }

    @Test
    void existingBoxSpaceAlsoAccountsForLooseStockAboveTheThreshold() {
        String item = "minecraft:stone";
        var inside = new WarehouseInventory.Entry(item, item, "Inside", 25 * 64, 0, 64);
        var loose = new WarehouseInventory.Entry(item, item, "Loose", 28 * 64, 0, 64);
        var stock = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(1, 64, 0)), Instant.ofEpochSecond(1),
                54, 29, 0, 1, 0, List.of(loose),
                List.of(new WarehouseInventory.Box(item, 25, List.of(inside))));
        var policy = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        assertFalse(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                Map.of(item, 64), Map.of(item, 64)));
        assertTrue(RecordedStorageCapacity.shouldReserveBox(stock, policy,
                Map.of(item, 65), Map.of(item, 64)));
    }

    @Test
    void countsOnlyCompatibleSlotsInTheSameCategoryBoxes() {
        String leaves = "minecraft:birch_leaves";
        var compatible = new WarehouseInventory.Entry(leaves, leaves, "Leaves", 48, 0, 64);
        var otherVariant = new WarehouseInventory.Entry(leaves + "#other", leaves,
                "Other leaves", 32, 0, 64);
        var box = new WarehouseInventory.Box(leaves, 26, List.of(compatible, otherVariant));
        var unrelated = new WarehouseInventory.Box("minecraft:stone", 1, List.of());
        var stock = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(1, 64, 0)), Instant.ofEpochSecond(1),
                54, 29, 0, 2, 0, List.of(), List.of(box, unrelated));

        assertEquals(64 + 16, RecordedStorageCapacity.existingBoxSpace(stock, leaves,
                Set.of(leaves), 64));
        assertEquals(64 + 16 + 32, RecordedStorageCapacity.existingBoxSpace(stock, leaves,
                Set.of(leaves, leaves + "#other"), 64));
    }

    @Test
    void keepsMultiplePartialStacksAndActualStackLimits() {
        String item = "minecraft:ender_pearl";
        var partial = new WarehouseInventory.Entry(item, item, "Pearl", 8, 0, 16);
        var box = new WarehouseInventory.Box(item, 26, List.of(partial, partial));
        var stock = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(1, 64, 0)), Instant.ofEpochSecond(1),
                54, 28, 0, 1, 0, List.of(), List.of(box));

        assertEquals(32, RecordedStorageCapacity.existingBoxSpace(stock, item, Set.of(item), 16));
    }

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
