package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageTransferBatchConfirmationTest {
    @Test
    void emptyBoxReturnUsesFreshServerPlayerCountsWhenAHopperDrainsTheDestination() {
        String key = "minecraft:black_shulker_box";
        var before = Map.of(key, new StorageTransferBatchConfirmation.Before(2, 0, 2));
        var receipt = new StorageTransferBatchConfirmation(before, Map.of(key, 1),
                false, false, true);

        assertFalse(receipt.matches(item -> 1, item -> 0, item -> 2, true));
        assertFalse(receipt.hasContainerProgress(item -> 0));
        assertTrue(receipt.requiresServerSnapshot(false));
        assertFalse(receipt.requiresServerSnapshot(true));
        assertTrue(receipt.acceptedServerPlayerSnapshot(item -> 1, item -> 2, true, false)
                .isEmpty());
        assertEquals(Map.of(key, 1), receipt.acceptedServerPlayerSnapshot(
                item -> 1, item -> 2, true, true).orElseThrow());
    }

    @Test
    void rejectedPredictedReturnsDoNotConsumeBoxesAndAnOccupiedCursorDoesNotConfirm() {
        String key = "minecraft:shulker_box";
        var before = Map.of(key, new StorageTransferBatchConfirmation.Before(2, 0, 0));
        var receipt = new StorageTransferBatchConfirmation(before, Map.of(key, 1),
                false, false, true);

        assertTrue(receipt.matches(item -> 1, item -> 1, item -> 0, true));
        assertTrue(receipt.requiresServerSnapshot(false));
        assertEquals(Map.of(), receipt.acceptedServerPlayerSnapshot(
                item -> 2, item -> 0, true, true).orElseThrow());
        assertTrue(receipt.acceptedServerPlayerSnapshot(item -> 1, item -> 0, false, true)
                .isEmpty());
        assertTrue(receipt.acceptedServerPlayerSnapshot(item -> 0, item -> 0, true, true)
                .isEmpty());
    }

    @Test
    void freshPlayerSnapshotsAccountForPickupsAndPartialAcceptancePerEmptyBoxKey() {
        String plain = "minecraft:shulker_box#plain";
        String black = "minecraft:black_shulker_box#black";
        var before = Map.of(plain, new StorageTransferBatchConfirmation.Before(3, 0, 10),
                black, new StorageTransferBatchConfirmation.Before(2, 4, 5));
        var receipt = new StorageTransferBatchConfirmation(before, Map.of(plain, 2, black, 1),
                false, false, true);
        Map<String, Integer> player = Map.of(plain, 3, black, 2);
        Map<String, Long> pickups = Map.of(plain, 11L, black, 5L);

        assertEquals(Map.of(plain, 1), receipt.acceptedServerPlayerSnapshot(
                player::get, pickups::get, true, true).orElseThrow());
        assertTrue(receipt.acceptedServerPlayerSnapshot(player::get,
                item -> before.get(item).pickups() - 1, true, true).isEmpty());
    }

    @Test
    void playerOnlyReceiptsRemainRestrictedToEmptyBoxReturns() {
        String box = "minecraft:shulker_box";
        var before = Map.of(box, new StorageTransferBatchConfirmation.Before(2, 0, 0));
        var ordinary = new StorageTransferBatchConfirmation(before, Map.of(box, 1), false, false);
        assertFalse(ordinary.requiresServerSnapshot(false));
        assertTrue(ordinary.acceptedServerPlayerSnapshot(item -> 1, item -> 0, true, true)
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new StorageTransferBatchConfirmation(
                before, Map.of(box, 1), true, false, true));
        String filled = "minecraft:shulker_box#box@minecraft:stone";
        assertThrows(IllegalArgumentException.class, () -> new StorageTransferBatchConfirmation(
                Map.of(filled, new StorageTransferBatchConfirmation.Before(1, 0, 0)),
                Map.of(filled, 1), false, false, true));
    }

    @Test
    void twentySevenDifferentBoxKeysShareOneConfirmationBarrier() {
        Map<String, StorageTransferBatchConfirmation.Before> before = new LinkedHashMap<>();
        Map<String, Integer> moved = new LinkedHashMap<>();
        for (int box = 0; box < 27; box++) {
            String key = "minecraft:shulker_box#" + box + "@minecraft:oak_log";
            before.put(key, new StorageTransferBatchConfirmation.Before(1, 0, 0));
            moved.put(key, 1);
        }
        var receipt = new StorageTransferBatchConfirmation(before, moved, false, false);

        assertTrue(receipt.matches(key -> 0, key -> 1, key -> 0, true));
        assertFalse(receipt.matches(key -> 0, key -> key.contains("#12@") ? 0 : 1,
                key -> 0, true));
        assertFalse(receipt.matches(key -> 0, key -> 1, key -> 0, false));
    }

    @Test
    void partialServerAcceptanceKeepsSeparateReceiptsForEachExactKey() {
        var before = Map.of("minecraft:shulker_box#first",
                new StorageTransferBatchConfirmation.Before(2, 0, 0),
                "minecraft:shulker_box#second",
                new StorageTransferBatchConfirmation.Before(3, 1, 0));
        var receipt = new StorageTransferBatchConfirmation(before,
                Map.of("minecraft:shulker_box#first", 2, "minecraft:shulker_box#second", 3),
                false, false);
        Map<String, Integer> actual = Map.of("minecraft:shulker_box#first", 2,
                "minecraft:shulker_box#second", 2);

        assertTrue(receipt.hasContainerProgress(key -> actual.get(key)));
        assertFalse(receipt.matchesServerContainer(key -> actual.get(key), true));
        assertEquals(Map.of("minecraft:shulker_box#first", 2, "minecraft:shulker_box#second", 1),
                receipt.acceptedServerSnapshot(key -> actual.get(key), true).orElseThrow());
        assertTrue(receipt.acceptedServerSnapshot(key -> actual.get(key), false).isEmpty());
    }

    @Test
    void pickupsAreNormalizedPerKeyAndEmptyBoxSuppliesKeepTheirSpecialConfirmation() {
        var before = Map.of("minecraft:oak_log", new StorageTransferBatchConfirmation.Before(2240, 0, 100));
        var receipt = new StorageTransferBatchConfirmation(before, Map.of("minecraft:oak_log", 512),
                false, false);

        assertTrue(receipt.matches(key -> 2240, key -> 512, key -> 612, true));
        assertFalse(receipt.matches(key -> 2240, key -> 512, key -> 599, true));
        assertTrue(receipt.matchesServerContainer(key -> 512, true));
        var supply = new StorageTransferBatchConfirmation(
                Map.of("box", new StorageTransferBatchConfirmation.Before(0, 54, 0)),
                Map.of("box", 1), true, true);
        assertTrue(supply.matches(key -> 1, key -> 54, key -> 0, true));
        assertFalse(supply.hasContainerProgress(key -> 53));
        assertTrue(supply.acceptedServerSnapshot(key -> 53, true).isEmpty());
    }
}
