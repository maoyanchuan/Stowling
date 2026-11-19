package dev.stowling.v2.model;

/** Count planning uses the actual stack limit (64, 16, or 1), never a fixed item count. */
public final class StoragePackingPlanner {
    public static final int BOX_SLOTS = 27;

    private StoragePackingPlanner() {
    }

    public static boolean unpackIncomingBox(boolean mixed, boolean full, StoragePolicy policy) {
        return mixed || !full && (policy.mode() != StorageMode.MIXED || policy.refillLoose());
    }

    public static int looseLimit(StoragePolicy policy, int containerSlots, int stackSize) {
        if (containerSlots < 1 || stackSize < 1) {
            throw new IllegalArgumentException("invalid container or stack capacity");
        }
        return switch (policy.mode()) {
            case NORMAL -> Math.multiplyExact(containerSlots, stackSize);
            case BOX_ONLY -> 0;
            case MIXED -> Math.multiplyExact(
                    policy.looseSlots() == null ? containerSlots * policy.loosePercent() / 100
                            : Math.min(containerSlots, policy.looseSlots()), stackSize);
        };
    }

    public static boolean canPack(boolean allowPartial, int available, int inside, int stackSize) {
        int needed = Math.multiplyExact(BOX_SLOTS, stackSize) - inside;
        return available > 0 && (allowPartial || available >= needed);
    }

    public static int refillCount(StoragePolicy policy, int slots, int stackSize, int loose,
            int otherLooseSlots, int available) {
        if (!policy.refillLoose() || policy.mode() != StorageMode.MIXED) {
            return 0;
        }
        int limit = Math.max(0, looseLimit(policy, slots, stackSize)
                - otherLooseSlots * stackSize);
        return Math.min(Math.max(0, available), Math.max(0, limit - loose));
    }

    public static long looseSpace(StoragePolicy policy, int slots, int stackSize,
            long sameCategoryLoose, long otherLooseSlots, int occupiedSlots, long matchingSlack) {
        long policySpace = looseLimit(policy, slots, stackSize)
                - otherLooseSlots * stackSize - sameCategoryLoose;
        long openSlots = Math.max(0, slots - occupiedSlots);
        return Math.max(0, Math.min(policySpace, openSlots * stackSize + matchingSlack));
    }

    public static boolean shouldBringBox(StoragePolicy policy, int incoming, long looseSpace,
            int stackSize, long excessAtTarget) {
        return shouldBringBox(policy, incoming, looseSpace, stackSize, excessAtTarget, 0);
    }

    public static boolean shouldBringBox(StoragePolicy policy, int incoming, long looseSpace,
            int stackSize, long excessAtTarget, long existingBoxSpace) {
        return shouldBringBox(policy, incoming, looseSpace, stackSize, excessAtTarget,
                existingBoxSpace, 0);
    }

    public static boolean shouldBringBox(StoragePolicy policy, int incoming, long looseSpace,
            int stackSize, long excessAtTarget, long existingBoxSpace, long targetLoose) {
        if (policy.mode() == StorageMode.NORMAL) {
            return false;
        }
        long remaining = Math.max(0, incoming + excessAtTarget - existingBoxSpace);
        if (!policy.allowPartialBoxes()) {
            long available = incoming + Math.max(excessAtTarget, targetLoose) - existingBoxSpace;
            return remaining > looseSpace && available >= (long) BOX_SLOTS * stackSize;
        }
        return remaining > looseSpace;
    }

    public static int targetItemsForPacking(StoragePolicy policy, int carried, int targetLoose,
            int looseLimit, int inside, int stackSize) {
        int excess = Math.max(0, targetLoose - looseLimit);
        int needed = Math.max(0, BOX_SLOTS * stackSize - inside - carried);
        if (!policy.allowPartialBoxes() && carried > 0 && needed <= targetLoose) {
            return Math.max(excess, needed);
        }
        return excess;
    }

}
