package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import org.junit.jupiter.api.Test;

class MinecraftShulkerActionTest {
    @Test
    void placingABoxUsesSideStancesWithoutStandingInItsColumn() {
        BlockPosition box = new BlockPosition(5, -43, 32);
        var stances = MinecraftShulkerAction.placementStances(box);

        assertTrue(stances.contains(box.offset(1, 0, 0)));
        assertTrue(stances.contains(box.offset(0, -1, -1)));
        assertFalse(stances.stream().anyMatch(feet -> feet.x() == box.x() && feet.z() == box.z()));
    }

    @Test
    void miningRequiresAStanceNextToTheBoxOnItsLevel() {
        BlockPosition box = new BlockPosition(5, -43, 32);
        AreaBounds area = MinecraftShulkerAction.miningArea(box);

        assertTrue(area.contains(box.offset(1, 0, 0)));
        assertTrue(area.contains(box.offset(0, 0, -1)));
        assertFalse(area.contains(box.offset(2, 0, 0)));
        assertFalse(area.contains(box.offset(1, 1, 0)));
    }
}
