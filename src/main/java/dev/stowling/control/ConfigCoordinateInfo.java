package dev.stowling.control;

public record ConfigCoordinateInfo(
        String key,
        String kind,
        String ownerId,
        String label,
        String dimension,
        ContainerPosition position
) {
}
