package dev.stowling.v2.model;

/** Opt-in experimental packing and pressure handling during dropped-item collection. */
public record CacheIntakePolicy(boolean enabled, Mode mode, int thresholdPercent) {
    public enum Mode {
        PACK_FIRST,
        CLASSIFY_FIRST
    }

    public static final CacheIntakePolicy DEFAULT = new CacheIntakePolicy(false, Mode.PACK_FIRST, 80);

    public CacheIntakePolicy(Mode mode, int thresholdPercent) {
        this(false, mode, thresholdPercent);
    }

    public CacheIntakePolicy {
        mode = mode == null ? Mode.PACK_FIRST : mode;
        if (thresholdPercent < 1 || thresholdPercent > 100) {
            throw new IllegalArgumentException("缓存容量阈值必须为 1 到 100 的整数百分比");
        }
    }
}
