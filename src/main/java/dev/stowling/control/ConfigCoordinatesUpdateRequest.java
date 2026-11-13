package dev.stowling.control;

import java.util.List;
import java.util.Objects;

public record ConfigCoordinatesUpdateRequest(
        String revision,
        List<ConfigCoordinateUpdate> coordinates
) {
    public ConfigCoordinatesUpdateRequest {
        revision = Objects.requireNonNull(revision, "revision");
        coordinates = List.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
    }
}
