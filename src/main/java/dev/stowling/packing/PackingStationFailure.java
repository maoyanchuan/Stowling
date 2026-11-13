package dev.stowling.packing;

import java.util.Objects;

public record PackingStationFailure(
        String stationId,
        PackingFailureStage stage,
        String reason
) {
    public PackingStationFailure {
        stationId = PackingChecks.requireText(stationId, "stationId");
        stage = Objects.requireNonNull(stage, "stage");
        reason = PackingChecks.requireText(reason, "reason");
    }
}
