package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class EmptyBoxSupplyPlan {
    private EmptyBoxSupplyPlan() {
    }

    public static boolean canReturnUnusedBoxes(java.util.Map<String, Integer> owned,
            Set<String> emptyBoxKeys) {
        return owned.entrySet().stream().noneMatch(entry -> entry.getValue() > 0
                && !emptyBoxKeys.contains(entry.getKey()));
    }

    public static List<ContainerTarget> sources(List<ContainerTarget> caches,
            ContainerTarget selected, List<BlockPosition> facilities) {
        List<ContainerTarget> candidates = new ArrayList<>();
        if (selected != null) {
            caches.stream().filter(target -> target.entrances().contains(selected.position()))
                    .findFirst().ifPresent(candidates::add);
        }
        candidates.addAll(caches);
        facilities.forEach(position -> candidates.add(new ContainerTarget(position, "container")));
        Set<BlockPosition> seen = new LinkedHashSet<>();
        List<ContainerTarget> result = new ArrayList<>();
        for (ContainerTarget target : candidates) {
            if (target.entrances().stream().noneMatch(seen::contains)) {
                result.add(target);
                seen.addAll(target.entrances());
            }
        }
        return List.copyOf(result);
    }
}
