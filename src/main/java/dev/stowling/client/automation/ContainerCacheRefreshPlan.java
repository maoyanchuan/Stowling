package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.RegionDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class ContainerCacheRefreshPlan {
    static final int MAX_SCAN_VOLUME = 262_144;

    private ContainerCacheRefreshPlan() {
    }

    static ContainerTarget next(Collection<ContainerTarget> pending, BlockPosition player,
            Predicate<BlockPosition> inReach) {
        ContainerTarget reachable = pending.stream()
                .filter(target -> target.entrances().stream().anyMatch(inReach))
                .min(Comparator.comparingDouble(target -> entranceDistance(target, player)))
                .orElse(null);
        return reachable != null ? reachable : pending.stream()
                .min(Comparator.comparingDouble(target -> travelDistance(target, player)))
                .orElseThrow();
    }

    private static double travelDistance(ContainerTarget target, BlockPosition player) {
        var area = target.standingArea();
        return area == null ? entranceDistance(target, player) : distance(player,
                new BlockPosition(Math.clamp(player.x(), area.minX(), area.maxX()),
                        Math.clamp(player.y(), area.minY(), area.maxY()),
                        Math.clamp(player.z(), area.minZ(), area.maxZ())));
    }

    private static double entranceDistance(ContainerTarget target, BlockPosition player) {
        return target.entrances().stream().mapToDouble(point -> distance(player, point))
                .min().orElseThrow();
    }

    private static double distance(BlockPosition first, BlockPosition second) {
        double dx = (double) first.x() - second.x();
        double dy = (double) first.y() - second.y();
        double dz = (double) first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }

    static List<RegionDefinition> chunks(UUID id, String name, AreaBounds bounds) {
        long width = (long) bounds.maxX() - bounds.minX() + 1;
        long height = (long) bounds.maxY() - bounds.minY() + 1;
        long depth = (long) bounds.maxZ() - bounds.minZ() + 1;
        if (width > MAX_SCAN_VOLUME || height > MAX_SCAN_VOLUME || depth > MAX_SCAN_VOLUME
                || width * height * depth > MAX_SCAN_VOLUME) {
            throw new IllegalArgumentException("选区“" + name + "”超过扫描体积上限 "
                    + MAX_SCAN_VOLUME);
        }
        List<RegionDefinition> result = new ArrayList<>();
        for (int x = bounds.minX() >> 4; x <= (bounds.maxX() >> 4); x++) {
            for (int z = bounds.minZ() >> 4; z <= (bounds.maxZ() >> 4); z++) {
                result.add(new RegionDefinition(id, name,
                        Math.max(bounds.minX(), x << 4), bounds.minY(),
                        Math.max(bounds.minZ(), z << 4),
                        Math.min(bounds.maxX(), (x << 4) + 15), bounds.maxY(),
                        Math.min(bounds.maxZ(), (z << 4) + 15)));
            }
        }
        return List.copyOf(result);
    }

    static BlockPosition chunkCenter(RegionDefinition chunk) {
        return new BlockPosition(((chunk.firstX() >> 4) << 4) + 8, chunk.firstY(),
                ((chunk.firstZ() >> 4) << 4) + 8);
    }

    static boolean markVisited(Set<BlockPosition> visited, ContainerTarget target) {
        boolean fresh = target.entrances().stream().noneMatch(visited::contains);
        visited.addAll(target.entrances());
        return fresh;
    }

    static ContainerTarget preferInside(ContainerTarget target, List<AreaBounds> areas) {
        if (target.partner() == null || areas.stream().anyMatch(
                area -> area.contains(target.position()))) {
            return target;
        }
        return new ContainerTarget(target.partner(), target.type(), target.standingArea(),
                target.storagePolicy(), target.position(), target.classificationStanding());
    }
}
