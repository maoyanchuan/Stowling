package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;

public record AppConfig(
        int schemaVersion,
        List<CoordinatePoint> coordinates,
        List<TaskDefinition> tasks,
        List<RegionDefinition> regions,
        List<CategoryZoneDefinition> categoryZones,
        List<TaskTriggerDefinition> triggers,
        List<TaskFolderDefinition> taskFolders,
        List<AutoClassifyDefinition> autoClassifyTasks,
        CoordinateCatalog coordinateCatalog
) {
    public static final int CURRENT_SCHEMA_VERSION = 16;

    public AppConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion: " + schemaVersion
            );
        }
        coordinateCatalog = coordinateCatalog == null ? CoordinateCatalog.EMPTY : coordinateCatalog;
        coordinates = List.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        regions = regions == null ? List.of() : List.copyOf(regions);
        categoryZones = categoryZones == null
                ? List.of()
                : List.copyOf(categoryZones);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        taskFolders = taskFolders == null
                ? List.of()
                : List.copyOf(taskFolders);
        autoClassifyTasks = autoClassifyTasks == null
                ? List.of()
                : List.copyOf(autoClassifyTasks);
    }

    public AppConfig(int schemaVersion, List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks, List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones, List<TaskTriggerDefinition> triggers,
            List<TaskFolderDefinition> taskFolders, List<AutoClassifyDefinition> autoClassifyTasks) {
        this(schemaVersion, coordinates, tasks, regions, categoryZones, triggers, taskFolders,
                autoClassifyTasks, CoordinateCatalog.EMPTY);
    }

    public AppConfig withCoordinateCatalog(CoordinateCatalog catalog) {
        return new AppConfig(schemaVersion, coordinates, tasks, regions, categoryZones, triggers,
                taskFolders, autoClassifyTasks, catalog);
    }

    public AppConfig(
            int schemaVersion,
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            List<TaskTriggerDefinition> triggers,
            List<TaskFolderDefinition> taskFolders
    ) {
        this(
                schemaVersion,
                coordinates,
                tasks,
                regions,
                categoryZones,
                triggers,
                taskFolders,
                List.of()
        );
    }

    public AppConfig(
            int schemaVersion,
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            List<TaskTriggerDefinition> triggers
    ) {
        this(
                schemaVersion,
                coordinates,
                tasks,
                regions,
                categoryZones,
                triggers,
                List.of(),
                List.of()
        );
    }

    public AppConfig(
            int schemaVersion,
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones
    ) {
        this(
                schemaVersion,
                coordinates,
                tasks,
                regions,
                categoryZones,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public AppConfig(
            int schemaVersion,
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks,
            List<RegionDefinition> regions
    ) {
        this(schemaVersion, coordinates, tasks, regions, List.of());
    }

    public AppConfig(
            int schemaVersion,
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks
    ) {
        this(schemaVersion, coordinates, tasks, List.of(), List.of());
    }

    public List<String> coordinateBookmarkIds() {
        var refs = new java.util.ArrayList<String>();
        coordinates.forEach(point -> refs.add("coordinate:" + point.id()));
        regions.forEach(region -> refs.add("region:" + region.id()));
        categoryZones.forEach(zone -> refs.add("category-zone:" + zone.id()));
        coordinateCatalog.warehouses().forEach(warehouse -> refs.add("warehouse:" + warehouse.id()));
        coordinateCatalog.folders().forEach(folder -> refs.add("folder:" + folder.id()));
        return List.copyOf(refs);
    }

    public static AppConfig empty() {
        return new AppConfig(
                CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
