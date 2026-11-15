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
    public static final String WAIT_FOR_DROPPED_ITEMS =
            "WAIT_FOR_DROPPED_ITEMS";
    public static final String COLLECT_DROPPED_ITEMS =
            "COLLECT_DROPPED_ITEMS";
    public static final String SCAN_CONTAINERS_IN_REGION =
            "SCAN_CONTAINERS_IN_REGION";
    public static final String DEPOSIT_ITEMS_TO_CONTAINERS =
            "DEPOSIT_ITEMS_TO_CONTAINERS";
    public static final String COORDINATE_ID = "coordinateId";
    public static final String REGION_ID = "regionId";
    public static final String ITEM_FILTER = "itemFilter";
    public static final String TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String CONTAINER_TYPES = "containerTypes";
    public static final String MAX_VOLUME = "maxVolume";
    public static final String SCAN_SOURCE = "scanSource";
    public static final String ITEM_SOURCE = "itemSource";
    public static final String FILL_MODE = "fillMode";

    private ConfigValidator() {
    }

    public static void requireValid(AppConfig config) {
        requireUniqueNames(
                config.coordinates().stream().map(CoordinatePoint::name).toList(),
                "coordinate"
        );
        requireUniqueNames(
                config.tasks().stream().map(TaskDefinition::name).toList(),
                "task"
        );
        requireUniqueNames(
                config.regions().stream().map(RegionDefinition::name).toList(),
                "region"
        );
        Map<UUID, CoordinatePoint> coordinates = new LinkedHashMap<>();
        for (CoordinatePoint point : config.coordinates()) {
            if (coordinates.putIfAbsent(point.id(), point) != null) {
                throw new V2ValidationException("duplicate coordinate id");
            }
        }
        Map<UUID, RegionDefinition> regions = new LinkedHashMap<>();
        for (RegionDefinition region : config.regions()) {
            if (regions.putIfAbsent(region.id(), region) != null) {
                throw new V2ValidationException("duplicate region id");
            }
        }
        Set<UUID> taskIds = new HashSet<>();
        for (TaskDefinition task : config.tasks()) {
            if (!taskIds.add(task.id())) {
                throw new V2ValidationException("duplicate task id");
            }
            for (TaskStep step : task.steps()) {
                requireValidStep(step, coordinates, regions);
            }
        }
    }

    public static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireValidStep(step, coordinates, Map.of());
    }

    public static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions
    ) {
        switch (step.moduleId()) {
            case MOVE_TO_COORDINATE -> requireCoordinateStep(
                    step,
                    coordinates,
                    Set.of(COORDINATE_ID),
                    Set.of(COORDINATE_ID)
            );
            case WAIT_FOR_DROPPED_ITEMS,
                    COLLECT_DROPPED_ITEMS -> requireCoordinateStep(
                    step,
                    coordinates,
                    Set.of(COORDINATE_ID, ITEM_FILTER, TIMEOUT_SECONDS),
                    Set.of(COORDINATE_ID)
            );
            case SCAN_CONTAINERS_IN_REGION -> requireScanStep(step, regions);
            case DEPOSIT_ITEMS_TO_CONTAINERS -> requireDepositStep(step);
            default -> throw new V2ValidationException(
                    "unknown task module: " + step.moduleId()
            );
        }
    }

    private static void requireCoordinateStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Set<String> allowed,
            Set<String> required
    ) {
        requireParameters(step, allowed, required);
        UUID coordinateId = requireUuid(
                step.parameters().get(COORDINATE_ID),
                COORDINATE_ID
        );
        if (!coordinates.containsKey(coordinateId)) {
            throw new V2ValidationException(
                    "coordinate does not exist: " + coordinateId
            );
        }
        requireOptionalInteger(step, TIMEOUT_SECONDS, 0, 86_400);
    }

    private static void requireScanStep(
            TaskStep step,
            Map<UUID, RegionDefinition> regions
    ) {
        requireParameters(
                step,
                Set.of(REGION_ID, CONTAINER_TYPES, MAX_VOLUME),
                Set.of(REGION_ID)
        );
        UUID regionId = requireUuid(
                step.parameters().get(REGION_ID),
                REGION_ID
        );
        if (!regions.containsKey(regionId)) {
            throw new V2ValidationException("region does not exist: " + regionId);
        }
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
    }

    private static void requireDepositStep(TaskStep step) {
        requireParameters(
                step,
                Set.of(SCAN_SOURCE, ITEM_SOURCE, FILL_MODE),
                Set.of(SCAN_SOURCE, ITEM_SOURCE, FILL_MODE)
        );
        requireChoice(step, SCAN_SOURCE, "previous-scan");
        requireChoice(step, ITEM_SOURCE, "collected-this-run");
        requireChoice(step, FILL_MODE, "nearest-first");
    }

    private static void requireParameters(
            TaskStep step,
            Set<String> allowed,
            Set<String> required
    ) {
        if (!allowed.containsAll(step.parameters().keySet())
                || !step.parameters().keySet().containsAll(required)) {
            throw new V2ValidationException(
                    step.moduleId() + " has invalid parameters"
            );
        }
    }

    private static UUID requireUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(field + " must be a UUID");
        }
    }

    private static void requireOptionalInteger(
            TaskStep step,
            String key,
            int minimum,
            int maximum
    ) {
        String value = step.parameters().get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new V2ValidationException(
                        key + " is outside the supported range"
                );
            }
        } catch (NumberFormatException exception) {
            throw new V2ValidationException(key + " must be an integer");
        }
    }

    private static void requireChoice(
            TaskStep step,
            String key,
            String expected
    ) {
        if (!expected.equals(step.parameters().get(key))) {
            throw new V2ValidationException(key + " is not supported");
        }
    }

    private static void requireUniqueNames(
            Iterable<String> names,
            String resource
    ) {
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            if (!normalized.add(normalizeName(name))) {
                throw new V2ValidationException(
                        resource + " name must be unique: " + name
                );
            }
        }
    }

    public static String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
