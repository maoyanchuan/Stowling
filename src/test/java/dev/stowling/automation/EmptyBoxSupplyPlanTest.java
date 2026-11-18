package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmptyBoxSupplyPlanTest {
    @Test
    void spareBoxesWaitUntilAllOtherItemsInTheBatchAreFinished() {
        String spare = "minecraft:shulker_box#empty";
        Set<String> emptyKeys = Set.of(spare);
        assertFalse(EmptyBoxSupplyPlan.canReturnUnusedBoxes(
                Map.of(spare, 1, "minecraft:stone", 64, "minecraft:birch_leaves", 32), emptyKeys));
        assertFalse(EmptyBoxSupplyPlan.canReturnUnusedBoxes(
                Map.of(spare, 1, "minecraft:birch_leaves", 32), emptyKeys));
        assertFalse(EmptyBoxSupplyPlan.canReturnUnusedBoxes(Map.of(spare, 1,
                "minecraft:shulker_box#filled@minecraft:birch_leaves", 1), emptyKeys));
        assertTrue(EmptyBoxSupplyPlan.canReturnUnusedBoxes(Map.of(spare, 2), emptyKeys));
        assertTrue(EmptyBoxSupplyPlan.canReturnUnusedBoxes(Map.of(), emptyKeys));
    }

    @Test
    void currentCacheAndOtherCachesPrecedePublicSupply() {
        ContainerTarget first = target(1);
        ContainerTarget selected = target(2);
        ContainerTarget supply = target(3);
        assertEquals(List.of(selected, first, supply), EmptyBoxSupplyPlan.sources(
                List.of(first, selected), selected, List.of(supply.position())));
    }

    @Test
    void doubleChestPartnerAndRepeatedFacilitiesAreNotVisitedTwice() {
        var pair = new ContainerTarget(position(1), "container", null, null, position(2));
        assertEquals(List.of(pair, target(3)), EmptyBoxSupplyPlan.sources(List.of(pair, pair),
                pair, List.of(position(2), position(3), position(3))));
    }

    @Test
    void anUnrelatedSelectedStorageChestDoesNotBecomeAnEmptyBoxSource() {
        assertEquals(List.of(target(1), target(3)), EmptyBoxSupplyPlan.sources(
                List.of(target(1)), target(99), List.of(position(3))));
    }

    @Test
    void selectingADoubleChestPartnerPreservesBothEntrances() {
        var pair = new ContainerTarget(position(1), "container", null, null, position(2));
        assertEquals(List.of(pair), EmptyBoxSupplyPlan.sources(List.of(pair), target(2),
                List.of(position(1), position(2))));
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(position(x), "container");
    }

    private static BlockPosition position(int x) {
        return new BlockPosition(x, 64, 0);
    }
}
