package dev.stowling.v2.model;

/** Controls what collection does when the cache reaches its occupied-slot threshold. */
public record CacheIntakePolicy(Mode mode, int thresholdPercent) {
    public enum Mode {
        PACK_FIRST,
        CLASSIFY_FIRST
    }

    public static final CacheIntakePolicy DEFAULT = new CacheIntakePolicy(Mode.PACK_FIRST, 80);

    public CacheIntakePolicy {
        mode = mode == null ? Mode.PACK_FIRST : mode;
        if (thresholdPercent < 1 || thresholdPercent > 100) {
            throw new IllegalArgumentException("缓存容量阈值必须为 1 到 100 的整数百分比");
        }
    }
}
