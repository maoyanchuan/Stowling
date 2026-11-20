package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CachePackingBatchPlanTest {
    @Test
    void fullBoxOnlyStillConsolidatesPartialsEvenBeforeTheyCanBeDelivered() {
        StoragePolicy fullOnly = new StoragePolicy(StorageMode.BOX_ONLY, 0, false, null);
        var split = CachePackingBatchPlan.calculate(fullOnly, 0, 3 * 64, 64, 64, 0, 0);
        assertEquals(2 * 64, split.nextLoad());
        assertEquals(0, split.plannedBoxes());
        var merged = CachePackingBatchPlan.calculate(fullOnly, 0, 3 * 64, 3 * 64, 64, 0, 0);
        assertEquals(0, merged.nextLoad());
        assertEquals(0, merged.plannedBoxes());
        assertEquals(64, CachePackingBatchPlan.calculate(fullOnly, 64, 64, 64, 64, 0, 0)
                .nextLoad());
    }

    @Test
    void secondaryBoxCategoriesMustCompleteTheirOwnCachePackingFirst() {
        for (StorageMode mode : new StorageMode[] {StorageMode.BOX_ONLY, StorageMode.MIXED}) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    CachePackingBatchPlan.canShareUnpreparedBatch(mode, false));
            org.junit.jupiter.api.Assertions.assertTrue(
                    CachePackingBatchPlan.canShareUnpreparedBatch(mode, true));
        }
        org.junit.jupiter.api.Assertions.assertTrue(
                CachePackingBatchPlan.canShareUnpreparedBatch(StorageMode.NORMAL, false));
    }

    private static final StoragePolicy BOXES = new StoragePolicy(StorageMode.BOX_ONLY, 0, true, null);

    @Test
    void eightyOneLooseStacksProduceThreeBoxesBeforeDelivery() {
        int loose = 81 * 64;
        int full = 0;
        var initial = CachePackingBatchPlan.calculate(BOXES, loose, 0, 0, 64, 0, full);
        assertEquals(3, initial.plannedBoxes());
        while (loose > 0) {
            var plan = CachePackingBatchPlan.calculate(BOXES, loose, 0, 0, 64, 0, full);
            assertEquals(27 * 64, plan.nextLoad());
            loose -= plan.nextLoad();
            full++;
        }
        assertEquals(3, full);
        assertEquals(0, CachePackingBatchPlan.calculate(BOXES, 0, 0, 0, 64, 0, full).nextLoad());
    }

    @Test
    void looseAndPartialBoxesAcrossCachesShareTheSameMaterialBudget() {
        int partial = (1 + 7 + 19) * 64;
        var plan = CachePackingBatchPlan.calculate(BOXES, 54 * 64, partial, 19 * 64, 64, 0, 0);

        assertEquals(3, plan.plannedBoxes());
        assertEquals(81 * 64, plan.materialToPack());
        assertEquals(8 * 64, plan.nextLoad());
        var tail = CachePackingBatchPlan.calculate(BOXES, 0, 3 * 64, 3 * 64, 64, 0, 0);
        assertEquals(1, tail.plannedBoxes());
        assertEquals(0, tail.nextLoad());
    }

    @Test
    void anIncompatibleStoneStackCannotUseTheLastOccupiedSlot() {
        String stone = "minecraft:stone";
        String variant = "minecraft:stone#named";
        var fullStack = new ShulkerTransferPlanner.Slot(stone, 64, 64);
        List<ShulkerTransferPlanner.Slot> largerBox = new ArrayList<>(
                Collections.nCopies(26, fullStack));
        largerBox.add(new ShulkerTransferPlanner.Slot(variant, 1, 64));
        List<ShulkerTransferPlanner.Slot> smallerBox = new ArrayList<>(
                Collections.nCopies(9, fullStack));
        smallerBox.add(new ShulkerTransferPlanner.Slot(stone, 37, 64));
        smallerBox.add(new ShulkerTransferPlanner.Slot(stone, 1, 64));
        Map<String, Integer> stock = new LinkedHashMap<>();
        stock.put(variant, 1);
        stock.put(stone, 2341);
        Map<String, Integer> sizes = Map.of(stone, 64, variant, 64);

        var nominal = CachePackingBatchPlan.calculate(BOXES, 0, 2342, 1665, 64, 0, 0);
        assertEquals(63, nominal.nextLoad());
        assertEquals(Map.of(), nominal.compatibleLoad(largerBox, stock, sizes, 1665));

        var alternative = CachePackingBatchPlan.calculate(BOXES, 0, 2342, 614, 64, 0, 0);
        assertEquals(Map.of(stone, 1114),
                alternative.compatibleLoad(smallerBox, stock, sizes, 1665));
    }

    @Test
    void aKeeperAtThePackingTargetStopsTransfersBetweenPartials() {
        String deepslate = "minecraft:deepslate";
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, true, null, true);
        Map<String, Integer> sizes = Map.of(deepslate, 64);
        var keeper = new ArrayList<>(Collections.nCopies(7,
                new ShulkerTransferPlanner.Slot(deepslate, 64, 64)));
        keeper.add(new ShulkerTransferPlanner.Slot(deepslate, 35, 64));
        Map<String, Integer> stock = Map.of(deepslate, 551 + 483);
        var plan = CachePackingBatchPlan.calculate(mixed, 729, 551 + 483, 483, 64, 1212, 0);

        assertEquals(68, plan.nextLoad());
        assertEquals(Map.of(deepslate, 68), plan.compatibleLoad(keeper, stock, sizes, 483));
        assertEquals(Map.of(), plan.compatibleLoad(keeper, stock, sizes, 551));
    }

    @Test
    void aPackingBatchUsesLooseSurplusUpToTheKeepersCapacity() {
        String stone = "minecraft:stone";
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, true, null);
        int loose = 2048;
        int inside = 640;
        var plan = CachePackingBatchPlan.calculate(mixed, loose, inside, inside, 64, 640, 0);
        int surplus = plan.loosePackingBudget(loose);
        var keeper = Collections.nCopies(10, new ShulkerTransferPlanner.Slot(stone, 64, 64));

        assertEquals(1408, surplus);
        assertEquals(Map.of(stone, 1088), plan.compatibleLoad(keeper,
                Map.of(stone, inside + surplus), Map.of(stone, 64), inside));
    }

    @Test
    void mixedModeKeepsItsLooseQuotaAndPacksOnlyTheRemainder() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        var plan = CachePackingBatchPlan.calculate(mixed, 81 * 64, 0, 0, 64, 27 * 64, 0);

        assertEquals(27 * 64, plan.looseReserved());
        assertEquals(54 * 64, plan.materialToPack());
        assertEquals(2, plan.plannedBoxes());
        assertEquals(3, CachePackingBatchPlan.calculate(mixed, 81 * 64, 0, 0, 64, 0, 0)
                .plannedBoxes());
    }

    @Test
    void mixedModeMakesOneFullBoxBeforeReservingTheLooseQuota() {
        StoragePolicy mixed = new StoragePolicy(StorageMode.MIXED, 50, false, null);
        int capacity = 27 * 64;
        var first = CachePackingBatchPlan.calculate(mixed, capacity + 64, 0, 0, 64, capacity, 0);

        assertEquals(capacity, first.nextLoad());
        assertEquals(1, first.plannedBoxes());
        assertEquals(64, first.looseReserved());

        var packed = CachePackingBatchPlan.calculate(mixed, capacity, 0, 0, 64, capacity, 1);
        assertEquals(0, packed.nextLoad());
        assertEquals(capacity, packed.looseReserved());
    }

    @Test
    void pureBoxModeMakesOneFullBoxEvenWhenWarehousePartialsCanAbsorbTheInput() {
        int capacity = 27 * 16;
        var shortLoad = CachePackingBatchPlan.calculate(BOXES, capacity - 1, 0, 0, 16, 0, 0,
                capacity, false);
        var fullLoad = CachePackingBatchPlan.calculate(BOXES, capacity, 0, 0, 16, 0, 0,
                capacity, false);

        assertEquals(0, shortLoad.nextLoad());
        assertEquals(capacity - 1, shortLoad.looseReserved());
        assertEquals(capacity, fullLoad.nextLoad());
        assertEquals(1, fullLoad.plannedBoxes());
    }

    @Test
    void refillingLooseFromBoxesRemainsAnExplicitMixedModeOption() {
        StoragePolicy off = new StoragePolicy(StorageMode.MIXED, 50, false, null, false);
        StoragePolicy on = new StoragePolicy(StorageMode.MIXED, 50, false, null, true);

        assertEquals(0, CachePackingBatchPlan.calculate(off, 0, 10 * 64, 10 * 64,
                64, 27 * 64, 0).looseReserved());
        assertEquals(10 * 64, CachePackingBatchPlan.calculate(on, 0, 10 * 64, 10 * 64,
                64, 27 * 64, 0).looseReserved());
    }

    @Test
    void knownWarehousePartialsKeepTheirRefillMaterialAndDoNotCreateCompetingCachePartials() {
        var plan = CachePackingBatchPlan.calculate(BOXES, 81 * 64, 0, 0, 64, 0, 0, 64, false);

        assertEquals(64, plan.looseReserved());
        assertEquals(2, plan.plannedBoxes());
        var tail = CachePackingBatchPlan.calculate(BOXES, 27 * 64, 0, 0, 64, 0, 2, 64, false);
        assertEquals(0, tail.nextLoad());
        assertEquals(2, tail.plannedBoxes());
    }

    @Test
    void stackLimitsAndTheMaximumBoxPayloadAreAppliedBeforeLoading() {
        for (int limit : new int[] {1, 16, 64}) {
            var plan = CachePackingBatchPlan.calculate(BOXES, 81 * limit, 0, 0, limit, 0, 0);
            assertEquals(3, plan.plannedBoxes());
            assertEquals(27 * limit, plan.nextLoad());
        }
        var full = CachePackingBatchPlan.calculate(BOXES, 1000 * 64, 0, 0, 64, 0, 27);
        assertEquals(27, full.plannedBoxes());
        assertEquals(0, full.nextLoad());
    }
}
