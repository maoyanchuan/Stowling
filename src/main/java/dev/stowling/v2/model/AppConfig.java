package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;

public record AppConfig(
        int schemaVersion,
        List<CoordinatePoint> coordinates,
        List<TaskDefinition> tasks,
        List<RegionDefinition> regions
) {
    public static final int CURRENT_SCHEMA_VERSION = 7;

    public AppConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion: " + schemaVersion
            );
        }
        coordinates = List.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    public AppConfig(
            int schemaVersion,
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks
    ) {
        this(schemaVersion, coordinates, tasks, List.of());
    }

    public static AppConfig empty() {
        return new AppConfig(
                CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
