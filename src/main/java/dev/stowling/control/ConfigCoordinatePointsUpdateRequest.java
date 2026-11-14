package dev.stowling.control;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ConfigCoordinatePointsUpdateRequest(
        String revision,
        List<ConfigCoordinatePointInfo> coordinatePoints,
        List<String> removeLegacyReferencesFor
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
        removeLegacyReferencesFor = List.copyOf(Objects.requireNonNull(
                removeLegacyReferencesFor,
                "removeLegacyReferencesFor"
        ));
        if (removeLegacyReferencesFor.size() > 4096
                || new HashSet<>(removeLegacyReferencesFor).size()
                != removeLegacyReferencesFor.size()
                || removeLegacyReferencesFor.stream().anyMatch(id ->
                !id.matches("[a-z0-9][a-z0-9._-]{0,63}"))) {
            throw new IllegalArgumentException("invalid coordinate cleanup ids");
        }
    }

    public ConfigCoordinatePointsUpdateRequest(
            String revision,
            List<ConfigCoordinatePointInfo> coordinatePoints
    ) {
        this(revision, coordinatePoints, List.of());
    }
}
