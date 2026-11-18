package dev.stowling.v2.model;

public record StoragePolicy(
        StorageMode mode,
        int loosePercent,
        boolean allowPartialBoxes,
        AreaBounds packingArea
) {
    public static final StoragePolicy DEFAULT =
            new StoragePolicy(StorageMode.NORMAL, 50, false, null);

    public StoragePolicy {
        mode = mode == null ? StorageMode.NORMAL : mode;
        if (loosePercent < 0 || loosePercent > 100) {
            throw new IllegalArgumentException("散装比例必须在 0 到 100 之间");
        }
    }

    public StoragePolicy shift(int x, int z) {
        return new StoragePolicy(mode, loosePercent, allowPartialBoxes,
                packingArea == null ? null : packingArea.shift(x, 0, z));
    }
}
