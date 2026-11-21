package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public final class ContainerVisitPlan {
    private final Map<BlockPosition, ContainerTarget> pending = new LinkedHashMap<>();

    public ContainerVisitPlan(Collection<ContainerTarget> scannedContainers) {
        for (ContainerTarget target : scannedContainers) {
            pending.putIfAbsent(target.position(), target);
        }
    }

    public Optional<ContainerTarget> next(BlockPosition playerPosition) {
        return next(playerPosition, target -> true);
    }

    public Optional<ContainerTarget> next(BlockPosition playerPosition,
            Predicate<ContainerTarget> eligible) {
        Optional<ContainerTarget> target = pending.values().stream().filter(eligible)
                .min(Comparator.comparingDouble(entry -> distanceSquared(playerPosition, entry)));
        target.ifPresent(entry -> pending.remove(entry.position()));
        return target;
    }

    private static double distanceSquared(BlockPosition origin, ContainerTarget target) {
        BlockPosition position = target.position();
        double dx = (double) position.x() - origin.x();
        double dy = (double) position.y() - origin.y();
        double dz = (double) position.z() - origin.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
