package dev.stowling.v2.model;

import java.util.UUID;

public record AutoEatOptions(
        boolean autoRefill,
        UUID coordinateId,
        int refillCount,
        boolean preferInventory
) {
    public static final int DEFAULT_REFILL_COUNT = 64;
    public static final int MAX_REFILL_COUNT = 2304;

    public AutoEatOptions {
        if (refillCount < 1 || refillCount > MAX_REFILL_COUNT) {
            throw new IllegalArgumentException("拿取数量必须在 1 到 2304 之间");
        }
        if (autoRefill && coordinateId == null) {
            throw new IllegalArgumentException("请先选择食物箱坐标");
        }
    }
}
