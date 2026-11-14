package dev.stowling.control;

import java.util.List;
import java.util.Objects;

public record ConfigCoordinatePointsUpdateRequest(
        String revision,
        List<ConfigCoordinatePointInfo> coordinatePoints
) {
    public ConfigCoordinatePointsUpdateRequest {
        revision = Objects.requireNonNull(revision, "revision");
        if (revision.isBlank()) {
            throw new IllegalArgumentException("revision must not be blank");
        }
        coordinatePoints = List.copyOf(Objects.requireNonNull(
                coordinatePoints,
                "coordinatePoints"
        ));
    }
}
