package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;

/** Nullable axes preserve incomplete coordinates while their feature is disabled. */
public record CategoryPositionDraft(Integer x, Integer y, Integer z) {
    public static CategoryPositionDraft of(BlockPosition position) {
        return position == null ? null
                : new CategoryPositionDraft(position.x(), position.y(), position.z());
    }

    public BlockPosition blockPosition() {
        if (x == null || y == null || z == null) {
            throw new IllegalArgumentException("请填写完整的 X、Y、Z 整数坐标");
        }
        return new BlockPosition(x, y, z);
    }
}
