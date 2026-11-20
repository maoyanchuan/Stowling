package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.UUID;

/** Position and exact stack identity while the picked entity still exists. */
public record PickupReceipt(
        UUID entityId, String itemId, int count, String dimension, double x, double y, double z
) {
    public DroppedItem drop() {
        return new DroppedItem(entityId, itemId, count, new BlockPosition(
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    public boolean within(double centerX, double centerY, double centerZ, int radius) {
        return x >= centerX - radius && x < centerX + radius
                && y >= centerY - radius && y < centerY + radius
                && z >= centerZ - radius && z < centerZ + radius;
    }
}
