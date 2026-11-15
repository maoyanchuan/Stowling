package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class TaskModuleSupport {
    static final int DEFAULT_DROP_RADIUS = 16;
    static final int DEFAULT_COLLECT_TIMEOUT_SECONDS = 120;
    static final int DEFAULT_SCAN_VOLUME = 262_144;

    private TaskModuleSupport() {
    }

    static CoordinatePoint coordinate(
            TaskExecutionContext context,
            TaskStep step
    ) {
        UUID id = UUID.fromString(
                step.parameters().get(ConfigValidator.COORDINATE_ID)
        );
        return Objects.requireNonNull(
                context.coordinates().get(id),
                "coordinate"
        );
    }

    static RegionDefinition region(
            TaskExecutionContext context,
            TaskStep step
    ) {
        UUID id = UUID.fromString(
                step.parameters().get(ConfigValidator.REGION_ID)
        );
        return Objects.requireNonNull(context.regions().get(id), "region");
    }

    static int dropRadius(CoordinatePoint coordinate) {
        return coordinate.radius() == null
                ? DEFAULT_DROP_RADIUS
                : coordinate.radius();
    }

    static int integerParameter(TaskStep step, String key, int fallback) {
        String value = step.parameters().get(key);
        return value == null || value.isBlank()
                ? fallback
                : Integer.parseInt(value);
    }

    static Set<String> itemFilter(TaskStep step) {
        return csvParameter(step, ConfigValidator.ITEM_FILTER);
    }

    static Set<String> containerTypes(TaskStep step) {
        return csvParameter(step, ConfigValidator.CONTAINER_TYPES);
    }

    static boolean dimensionMatches(
            TaskExecutionContext context,
            CoordinatePoint coordinate
    ) {
        return coordinate.dimension() == null
                || context.navigation().isInDimension(coordinate.dimension());
    }

    static boolean itemMatches(String itemId, Set<String> filter) {
        if (filter.isEmpty()) {
            return true;
        }
        int separator = itemId.indexOf(':');
        String path = separator < 0 ? itemId : itemId.substring(separator + 1);
        return filter.contains(itemId) || filter.contains(path);
    }

    private static Set<String> csvParameter(TaskStep step, String key) {
        String value = step.parameters().get(key);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Set.copyOf(result);
    }
}
