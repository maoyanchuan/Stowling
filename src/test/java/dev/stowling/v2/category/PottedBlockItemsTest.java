package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PottedBlockItemsTest {
    @Test
    void resolvesAPottedSaplingToItsInventoryItem() {
        assertEquals(
                "minecraft:oak_sapling",
                PottedBlockItems.itemId("minecraft:potted_oak_sapling")
                        .orElseThrow()
        );
    }

    @Test
    void handlesPottedBlocksWhoseItemNameIsNotTheSuffix() {
        assertEquals(
                "minecraft:azalea",
                PottedBlockItems.itemId("minecraft:potted_azalea_bush")
                        .orElseThrow()
        );
        assertEquals(
                "minecraft:flowering_azalea",
                PottedBlockItems.itemId(
                        "minecraft:potted_flowering_azalea_bush"
                ).orElseThrow()
        );
    }

    @Test
    void ignoresOrdinaryAndEmptyFlowerPotBlocks() {
        assertTrue(PottedBlockItems.itemId("minecraft:flower_pot").isEmpty());
        assertTrue(PottedBlockItems.itemId("minecraft:oak_sapling").isEmpty());
    }
}
