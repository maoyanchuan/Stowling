package dev.stowling.packing;

import java.util.Objects;

public record PackingStationReservation(
        String taskId,
        String itemId,
        PackingWorkcell workcell,
        long distanceSquared,
        long sequence
) {
    public PackingStationReservation {
        taskId = PackingChecks.requireText(taskId, "taskId");
        itemId = PackingChecks.requireText(itemId, "itemId");
        workcell = Objects.requireNonNull(workcell, "workcell");
        if (distanceSquared < 0) {
            throw new IllegalArgumentException("distanceSquared must not be negative");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    public String stationId() {
        return workcell.station().id();
    }
}
