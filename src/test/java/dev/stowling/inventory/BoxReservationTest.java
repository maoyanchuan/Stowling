package dev.stowling.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoxReservationTest {
    private static final String LEAVES = "minecraft:birch_leaves";
    private static final StoragePolicy FULL_ONLY =
            new StoragePolicy(StorageMode.MIXED, 50, false, null);

    @Test
    void reservesWhenTargetIsAlreadyOverHalfWithoutRequiringAFullNewBox() {
        assertTrue(reserve(stock(27, 14 * 64, List.of()), FULL_ONLY, 1, 64));
    }

    @Test
    void reservesWhenThisCacheBatchTakesTheTargetPastHalf() {
        var stock = stock(54, 26 * 64, List.of());
        assertFalse(reserve(stock, FULL_ONLY, 64, 64));
        assertTrue(reserve(stock, FULL_ONLY, 65, 64));
    }

    @Test
    void reservesForOverflowFromAPartialBoxEvenBelowTheLooseThreshold() {
        var stock = stock(54, 0, List.of(box(26 * 64, 64)));
        assertFalse(reserve(stock, FULL_ONLY, 64, 64));
        assertTrue(reserve(stock, FULL_ONLY, 65, 64));
        assertTrue(reserve(stock, FULL_ONLY, 2 * 64, 64));
    }

    @Test
    void partialBoxRoomDoesNotSuppressReservationWhenLooseStockExceedsHalf() {
        var stock = stock(54, 27 * 64, List.of(box(1, 64)));
        assertTrue(reserve(stock, FULL_ONLY, 64, 64));
    }

    @Test
    void reservationWorksWithBothPartialBoxSettingsAndActualStackSizes() {
        var partialAllowed = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        var stock = stock(54, 0, List.of(box(26 * 16, 16)));
        assertFalse(reserve(stock, partialAllowed, 16, 16));
        assertTrue(reserve(stock, partialAllowed, 17, 16));
        assertTrue(reserve(stock, FULL_ONLY, 17, 16));
    }

    @Test
    void fullBoxesDoNotTriggerAnExtraReservationBelowTheLooseLimit() {
        assertFalse(reserve(stock(54, 0, List.of(box(27 * 64, 64))), FULL_ONLY, 64, 64));
    }

    @Test
    void unknownTargetStockDoesNotCauseSpeculativeSupplyTrips() {
        assertFalse(reserve(null, FULL_ONLY, 1, 64));
        assertFalse(reserve(null, StoragePolicy.DEFAULT, 64, 64));
        assertFalse(RecordedStorageCapacity.shouldReserveBox(null, FULL_ONLY,
                Map.of("minecraft:shulker_box#full@" + LEAVES, 1), Map.of()));
        assertFalse(RecordedStorageCapacity.shouldReserveBox(null, FULL_ONLY, Map.of(), Map.of()));
    }

    @Test
    void incompletePartialBoxMetadataDoesNotMeanTheBoxWillOverflow() {
        var stock = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(0, 64, 0)), Instant.ofEpochSecond(1),
                54, 2, 0, 1, 0,
                List.of(new WarehouseInventory.Entry(LEAVES, LEAVES, "Leaves", 64, 0, 64)));
        assertFalse(reserve(stock, FULL_ONLY, 64, 64));
    }

    @Test
    void unknownStackSizeDoesNotForceABoxForEveryCategory() {
        assertFalse(RecordedStorageCapacity.shouldReserveBox(stock(54, 0, List.of()),
                FULL_ONLY, Map.of("minecraft:dirt", 64), Map.of()));
    }

    @Test
    void belowThresholdTripsDoNotFetchEmptyBoxesWithEitherRefillSetting() {
        var stock = stock(54, 5 * 64, List.of());
        assertFalse(reserve(stock, FULL_ONLY, 2 * 64, 64));
        var refill = new StoragePolicy(StorageMode.MIXED, 50, false, null, true);
        assertFalse(reserve(stock, refill, 2 * 64, 64));
    }

    @Test
    void unrecordedSnapshotsDoNotCauseAnEmptyBoxToBeFetched() {
        var unrecorded = new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(0, 64, 0)), null, 54, 0, 0, 0, 0, List.of());
        assertFalse(reserve(unrecorded, FULL_ONLY, 64, 64));
    }

    @Test
    void unrelatedCategoryPartialBoxesDoNotTriggerAReservation() {
        var other = new WarehouseInventory.Box("minecraft:stone", 1,
                List.of(new WarehouseInventory.Entry("minecraft:stone", "minecraft:stone",
                        "Stone", 1, 0, 64)));
        assertFalse(reserve(stock(54, 0, List.of(other)), FULL_ONLY, 64, 64));
    }

    private static boolean reserve(WarehouseInventory.Container stock, StoragePolicy policy,
            int incoming, int stackSize) {
        return RecordedStorageCapacity.shouldReserveBox(stock, policy,
                Map.of(LEAVES, incoming), Map.of(LEAVES, stackSize));
    }

    private static WarehouseInventory.Container stock(int slots, long loose,
            List<WarehouseInventory.Box> boxes) {
        int occupied = (int) Math.ceilDiv(loose, 64) + boxes.size();
        int partial = (int) boxes.stream().filter(box -> box.occupiedSlots() < 27
                || box.items().stream().anyMatch(entry -> entry.loose() < entry.stackSize()))
                .count();
        return new WarehouseInventory.Container("chest", "Chest", "minecraft:overworld",
                List.of(new BlockPosition(0, 64, 0)), Instant.ofEpochSecond(1), slots,
                occupied, boxes.size() - partial, partial, 0,
                List.of(new WarehouseInventory.Entry(LEAVES, LEAVES, "Leaves", loose, 0, 64)),
                boxes);
    }

    private static WarehouseInventory.Box box(int amount, int stackSize) {
        List<WarehouseInventory.Entry> entries = new ArrayList<>();
        for (int remaining = amount; remaining > 0; remaining -= stackSize) {
            entries.add(new WarehouseInventory.Entry(LEAVES, LEAVES, "Leaves",
                    Math.min(remaining, stackSize), 0, stackSize));
        }
        return new WarehouseInventory.Box(LEAVES, entries.size(), entries);
    }
}
