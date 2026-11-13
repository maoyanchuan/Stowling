package dev.stowling.control;

import java.util.List;

public record ConfigCoordinatesSnapshot(
        String revision,
        List<ConfigCoordinateInfo> coordinates
) {
    public ConfigCoordinatesSnapshot {
        coordinates = List.copyOf(coordinates);
    }
}
