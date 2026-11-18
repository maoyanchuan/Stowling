package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MixedBoxIdentityTest {
    @Test
    void separatesMixedBoxesFromEmptyAndSingleKindBoxes() {
        assertEquals("", ItemIdentity.boxContentsCategory(Set.of()));
        assertEquals("minecraft:stone", ItemIdentity.boxContentsCategory(Set.of("minecraft:stone")));
        assertEquals("minecraft:stone", ItemIdentity.boxContentsCategory(
                Set.of("minecraft:stone#first", "minecraft:stone#second")));
        assertEquals(ItemIdentity.MIXED_BOX_CATEGORY, ItemIdentity.boxContentsCategory(
                Set.of("minecraft:stone", "minecraft:birch_leaves")));
    }

    @Test
    void mixedBoxesAreNotMistakenForOneOfTheirContentsOrForEmptySupplies() {
        String mixed = "minecraft:blue_shulker_box#contents@" + ItemIdentity.MIXED_BOX_CATEGORY;
        assertTrue(ItemIdentity.isMixedBox(mixed));
        assertFalse(ItemIdentity.isMixedBox("minecraft:blue_shulker_box#empty"));
        assertFalse(ItemIdentity.isMixedBox("minecraft:blue_shulker_box#x@minecraft:stone"));
        assertFalse(ItemIdentity.matches(mixed, Set.of("minecraft:stone", "minecraft:birch_leaves")));
        assertFalse(ItemIdentity.isMixedBox("minecraft:stone#x@" + ItemIdentity.MIXED_BOX_CATEGORY));
    }
}
