package dev.stowling.control;

import java.util.List;
import java.util.Objects;

public record ConfigCoordinatePointsSnapshot(
        String revision,
        List<ConfigCoordinatePointInfo> coordinatePoints
) {
    public ConfigCoordinatePointsSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        coordinatePoints = List.copyOf(Objects.requireNonNull(
                coordinatePoints,
                "coordinatePoints"
        ));
    }
}
