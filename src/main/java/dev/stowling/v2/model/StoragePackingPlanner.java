package dev.stowling.v2.model;

/** Count planning uses the actual stack limit (64, 16, or 1), never a fixed item count. */
public final class StoragePackingPlanner {
    public static final int BOX_SLOTS = 27;

    private StoragePackingPlanner() {
    }

    public static int looseLimit(StoragePolicy policy, int containerSlots, int stackSize) {
        if (containerSlots < 1 || stackSize < 1) {
            throw new IllegalArgumentException("invalid container or stack capacity");
        }
        return switch (policy.mode()) {
            case NORMAL -> Math.multiplyExact(containerSlots, stackSize);
            case BOX_ONLY -> 0;
            case MIXED -> Math.multiplyExact(
                    containerSlots * policy.loosePercent() / 100, stackSize);
        };
    }

    public static boolean canPack(boolean allowPartial, int available, int inside, int stackSize) {
        int needed = Math.multiplyExact(BOX_SLOTS, stackSize) - inside;
        return available > 0 && (allowPartial || available >= needed);
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
        if (policy.mode() == StorageMode.NORMAL) {
            return false;
        }
        if (!policy.allowPartialBoxes()) {
            return (incoming > looseSpace || excessAtTarget > 0)
                    && incoming + excessAtTarget >= (long) BOX_SLOTS * stackSize;
        }
        return incoming > looseSpace || excessAtTarget > 0;
    }

}
