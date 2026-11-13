package dev.stowling.packing;

import java.util.Objects;

public record PackingStationCandidate(
        PackingWorkcell workcell,
        boolean reachable,
        boolean operational,
        long distanceSquared
) {
    public PackingStationCandidate {
        workcell = Objects.requireNonNull(workcell, "workcell");
        if (distanceSquared < 0) {
            throw new IllegalArgumentException("distanceSquared must not be negative");
        }
    }
}
