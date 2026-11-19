package dev.stowling.automation;

import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePackingPlanner;
import dev.stowling.v2.model.StoragePolicy;

/** Shared cache-side planning; partial boxes contribute material rather than separate trips. */
public record CachePackingBatchPlan(int looseReserved, int materialToPack, int plannedBoxes,
        int nextLoad) {
    public static CachePackingBatchPlan calculate(StoragePolicy policy, int loose, int partialItems,
            int keeperItems, int stackSize, int looseDeficit, int fullBoxes) {
        return calculate(policy, loose, partialItems, keeperItems, stackSize, looseDeficit,
                fullBoxes, 0, true);
    }

    public static CachePackingBatchPlan calculate(StoragePolicy policy, int loose, int partialItems,
            int keeperItems, int stackSize, int looseDeficit, int fullBoxes,
            int existingBoxSpace, boolean finishPartial) {
        if (loose < 0 || partialItems < 0 || keeperItems < 0 || stackSize < 1 || fullBoxes < 0) {
            throw new IllegalArgumentException("invalid cache packing counts");
        }
        int capacity = Math.multiplyExact(StoragePackingPlanner.BOX_SLOTS, stackSize);
        int total = Math.addExact(loose, partialItems);
        int reserve = policy.mode() != StorageMode.MIXED ? 0
                : Math.min(policy.refillLoose() ? total : loose, Math.max(0, looseDeficit));
        reserve = Math.toIntExact(Math.min((long) total,
                (long) reserve + Math.max(0, existingBoxSpace)));
        int packing = Math.max(0, total - reserve);
        if (policy.mode() == StorageMode.NORMAL) return new CachePackingBatchPlan(total, 0, 0, 0);
        boolean partial = policy.allowPartialBoxes() && finishPartial;
        int boxes = packing / capacity + (partial && packing % capacity > 0 ? 1 : 0);
        int planned = Math.min(StoragePackingPlanner.BOX_SLOTS, fullBoxes + boxes);
        int load = fullBoxes >= StoragePackingPlanner.BOX_SLOTS
                || !partial && packing < capacity ? 0
                : Math.max(0, Math.min(capacity, packing) - keeperItems);
        return new CachePackingBatchPlan(reserve, packing, planned, load);
    }
}
