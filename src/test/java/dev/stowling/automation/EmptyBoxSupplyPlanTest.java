package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;


    @Test
    void aReturnBatchIncludesAllOwnedEmptyColorsAndRespectsTheRemainingCacheQuota() {
        String plain = "minecraft:shulker_box#plain";
        String black = "minecraft:black_shulker_box#black";
        String filled = "minecraft:shulker_box#filled@minecraft:stone";
        var owned = new java.util.LinkedHashMap<String, Integer>();
        owned.put(plain, 16);
        owned.put(black, 2);
        owned.put(filled, 1);
        owned.put("minecraft:stone", 64);

        assertEquals(Map.of(plain, 16, black, 2),
                EmptyBoxSupplyPlan.returnBatch(owned, Integer.MAX_VALUE));
        assertEquals(Map.of(plain, 3), EmptyBoxSupplyPlan.returnBatch(owned, 3));
        assertEquals(Map.of(), EmptyBoxSupplyPlan.returnBatch(owned, 0));
        assertEquals(16, owned.get(plain));
        assertEquals(1, owned.get(filled));
    }

    @Test
    void warehouseAndRetentionSwitchesSelectTheExpectedReturnLocations() {
        var caches = List.of(target(1), target(2));
        var warehouse = List.of(target(3));
        assertEquals(caches, EmptyBoxSupplyPlan.classificationReturns(
                caches, warehouse, false, 20, 9));
        assertEquals(warehouse, EmptyBoxSupplyPlan.classificationReturns(
                caches, warehouse, true, 0, 0));
        assertEquals(List.of(target(1), target(2), target(3)),
                EmptyBoxSupplyPlan.classificationReturns(caches, warehouse, true, 8, 9));
        assertEquals(warehouse, EmptyBoxSupplyPlan.classificationReturns(
                caches, warehouse, true, 9, 9));
    }

    @Test
    void surplusCountsAllColorsAndMovesOnlyEmptyShellsThatFitTheInventory() {
        var stock = new java.util.LinkedHashMap<String, Integer>();
        stock.put("minecraft:shulker_box#splain", 7);
        stock.put("minecraft:red_shulker_box#sred", 8);
        stock.put("minecraft:shulker_box#sfilled@minecraft:stone", 4);
        stock.put("minecraft:stone", 64);
        assertEquals(15, EmptyBoxSupplyPlan.emptyCount(stock));
        assertEquals(Map.of("minecraft:red_shulker_box#sred", 3),
                EmptyBoxSupplyPlan.surplus(stock, 9, 3));
        assertEquals(Map.of(), EmptyBoxSupplyPlan.surplus(stock, 20, 9));
        assertEquals(Map.of(), EmptyBoxSupplyPlan.surplus(stock, 0, 0));
    }

    @Test
    void emptiedRefillBoxesReturnToCachesOrSupplyAndNeverToClassificationStorage() {
        ContainerTarget cache = target(1);
        ContainerTarget supply = target(2);
        ContainerTarget classification = target(3);
        ContainerTarget fallback = target(4);

        assertEquals(List.of(cache, supply, fallback), EmptyBoxSupplyPlan.returns(cache,
                List.of(cache, classification, supply), List.of(classification, fallback),
                Set.of(classification.position()), Set.of()));
        assertEquals(List.of(cache, supply, fallback), EmptyBoxSupplyPlan.returns(classification,
                List.of(cache, supply), List.of(fallback), Set.of(classification.position()),
                Set.of()));
    }

    @Test
    void emptyDonorBoxesSkipDedicatedPartialStorageEvenWhenPreferredOrUsedForCleanup() {
        var partial = new ContainerTarget(position(10), "minecraft:chest", null, null,
                position(11));
        ContainerTarget cache = target(1);
        ContainerTarget supply = target(2);
        ContainerTarget cleanup = target(3);

        assertEquals(List.of(cache, supply, cleanup), EmptyBoxSupplyPlan.returns(partial,
                List.of(partial, cache, supply), List.of(target(11), cleanup), Set.of(),
                Set.copyOf(partial.entrances())));
        assertTrue(EmptyBoxSupplyPlan.returns(partial, List.of(), List.of(), Set.of(),
                Set.of(position(11))).isEmpty());
    }

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
