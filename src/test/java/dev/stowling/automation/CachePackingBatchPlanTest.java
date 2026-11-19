package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
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
