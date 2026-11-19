package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageTransferBatchConfirmationTest {
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
