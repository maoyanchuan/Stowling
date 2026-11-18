package dev.stowling.v2.model;

/** Count planning uses the actual stack limit (64, 16, or 1), never a fixed item count. */
public final class StoragePackingPlanner {
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
        int needed = Math.multiplyExact(27, stackSize) - inside;
        return available > 0 && (allowPartial || available >= needed);
    }
}
