package dev.stowling.automation;

import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePackingPlanner;
import dev.stowling.v2.model.StoragePolicy;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (policy.mode() == StorageMode.NORMAL) return new CachePackingBatchPlan(total, 0, 0, 0);
        int reserve = policy.mode() != StorageMode.MIXED ? 0
                : Math.min(policy.refillLoose() ? total : loose, Math.max(0, looseDeficit));
        reserve = Math.toIntExact(Math.min((long) total,
                (long) reserve + Math.max(0, existingBoxSpace)));
        if (fullBoxes == 0 && total >= capacity) {
            reserve = Math.min(reserve, total - capacity);
        }
        int packing = Math.max(0, total - reserve);
        boolean partial = policy.allowPartialBoxes() && finishPartial;
        int boxes = packing / capacity + (partial && packing % capacity > 0 ? 1 : 0);
        int planned = Math.min(StoragePackingPlanner.BOX_SLOTS, fullBoxes + boxes);
        boolean needsConsolidation = partialItems > 0 && packing > keeperItems;
        int load = fullBoxes >= StoragePackingPlanner.BOX_SLOTS
                || !partial && packing < capacity && !needsConsolidation ? 0
                : Math.max(0, Math.min(capacity, packing) - keeperItems);
        return new CachePackingBatchPlan(reserve, packing, planned, load);
    }

    public int loosePackingBudget(int looseItems) {
        return Math.max(0, looseItems - looseReserved);
    }

    /** Only plans transfers that leave a larger consolidated box. */
    public Map<String, Integer> compatibleLoad(List<ShulkerTransferPlanner.Slot> keeper,
            Map<String, Integer> stock, Map<String, Integer> stackSizes, int largestPartial) {
        int inside = keeper.stream().mapToInt(ShulkerTransferPlanner.Slot::count).sum();
        if (nextLoad <= 0 || inside + nextLoad <= largestPartial) {
            return Map.of();
        }
        Map<String, Integer> available = new LinkedHashMap<>();
        for (var slot : keeper) {
            int count = available.getOrDefault(slot.key(), stock.getOrDefault(slot.key(), 0));
            available.put(slot.key(), count - slot.count());
        }
        stock.entrySet().stream().filter(entry -> !available.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> available.put(entry.getKey(), entry.getValue()));
        var fitting = ShulkerTransferPlanner.fitting(keeper, StoragePackingPlanner.BOX_SLOTS,
                available, stackSizes);
        Map<String, Integer> load = new LinkedHashMap<>();
        int remaining = nextLoad;
        for (var entry : fitting.entrySet()) {
            if (remaining <= 0) break;
            int count = Math.min(remaining, entry.getValue());
            load.put(entry.getKey(), count);
            remaining -= count;
        }
        return inside + nextLoad - remaining > largestPartial ? load : Map.of();
    }

    public static boolean canShareUnpreparedBatch(StorageMode mode, boolean fullBox) {
        return mode == StorageMode.NORMAL || fullBox;
    }
}
