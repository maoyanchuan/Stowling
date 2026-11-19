package dev.stowling.automation;

import java.util.ArrayList;
import java.util.List;

/** Dedicated partial-box regions participate in source scanning, never as warehouse outputs. */
public final class PartialBoxSourcePlan {
    private PartialBoxSourcePlan() {
    }

    public static List<ContainerTarget> combine(List<ContainerTarget> caches,
            List<ContainerTarget> partials, List<ContainerTarget> outputs) {
        List<ContainerTarget> candidates = new ArrayList<>(caches);
        partials.stream().filter(source -> outputs.stream().noneMatch(output ->
                output.entrances().stream().anyMatch(source.entrances()::contains)))
                .forEach(candidates::add);
        return EmptyBoxSupplyPlan.sources(candidates, null, List.of());
    }
}
