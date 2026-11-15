package dev.stowling.v2.config;

import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ConfigValidator {
    public static final String MOVE_TO_COORDINATE = "MOVE_TO_COORDINATE";
    public static final String COORDINATE_ID = "coordinateId";

    private ConfigValidator() {
    }

    public static void requireValid(AppConfig config) {
        requireUniqueNames(
                config.coordinates().stream().map(CoordinatePoint::name).toList(),
                "coordinate"
        );
        requireUniqueNames(config.tasks().stream().map(TaskDefinition::name).toList(), "task");
        requireUniqueNames(config.regions().stream().map(RegionDefinition::name).toList(), "region");
        Map<UUID, CoordinatePoint> coordinates = new LinkedHashMap<>();
        for (CoordinatePoint point : config.coordinates()) {
            if (coordinates.putIfAbsent(point.id(), point) != null) {
                throw new V2ValidationException("duplicate coordinate id");
            }
        }
        Set<UUID> taskIds = new HashSet<>();
        Set<UUID> regionIds = new HashSet<>();
        for (RegionDefinition region : config.regions()) {
            if (!regionIds.add(region.id())) {
                throw new V2ValidationException("duplicate region id");
            }
        }
        for (TaskDefinition task : config.tasks()) {
            if (!taskIds.add(task.id())) {
                throw new V2ValidationException("duplicate task id");
            }
            for (TaskStep step : task.steps()) {
                requireValidStep(step, coordinates);
            }
        }
    }

    public static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        if (!MOVE_TO_COORDINATE.equals(step.moduleId())) {
            throw new V2ValidationException("unknown task module: " + step.moduleId());
        }
        if (!step.parameters().keySet().equals(Set.of(COORDINATE_ID))) {
            throw new V2ValidationException(
                    "MOVE_TO_COORDINATE requires only coordinateId"
            );
        }
        String value = step.parameters().get(COORDINATE_ID);
        final UUID coordinateId;
        try {
            coordinateId = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException("coordinateId must be a UUID");
        }
        if (!coordinates.containsKey(coordinateId)) {
            throw new V2ValidationException("coordinate does not exist: " + coordinateId);
        }
    }

    private static void requireUniqueNames(Iterable<String> names, String resource) {
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            if (!normalized.add(normalizeName(name))) {
                throw new V2ValidationException(resource + " name must be unique: " + name);
            }
        }
    }

    public static String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
