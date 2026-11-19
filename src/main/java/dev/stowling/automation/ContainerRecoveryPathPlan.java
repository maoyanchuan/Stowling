package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A moving item is an observation, never an unchecked pathfinding destination. */
public final class ContainerRecoveryPathPlan {
    private ContainerRecoveryPathPlan() {
    }

    public static boolean withinArea(BlockPosition site, BlockPosition drop, int minY, int maxY) {
        return drop.y() >= minY && drop.y() < maxY
                && Math.abs((long) drop.x() - site.x()) <= 4
                && Math.abs((long) drop.y() - site.y()) <= 8
                && Math.abs((long) drop.z() - site.z()) <= 4;
    }

    public static Optional<BlockPosition> select(BlockPosition site, BlockPosition drop,
            BlockPosition player, List<BlockPosition> supportedFeet, int minY, int maxY) {
        if (!withinArea(site, drop, minY, maxY)) return Optional.empty();
        return supportedFeet.stream().filter(feet -> withinArea(site, feet, minY, maxY))
                .filter(feet -> Math.abs((long) feet.x() - drop.x()) <= 1
                        && Math.abs((long) feet.z() - drop.z()) <= 1
                        && drop.y() >= feet.y() && drop.y() <= (long) feet.y() + 1)
                .min(Comparator.comparingLong((BlockPosition feet) -> distance(feet, drop))
                        .thenComparingLong(feet -> distance(feet, player)));
    }

    private static long distance(BlockPosition first, BlockPosition second) {
        long dx = (long) first.x() - second.x();
        long dy = (long) first.y() - second.y();
        long dz = (long) first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
