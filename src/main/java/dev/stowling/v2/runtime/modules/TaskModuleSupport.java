package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.category.CategoryMarkerSource;
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
                step.parameters().get(TaskModuleIds.COORDINATE_ID)
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
        return region(context, step, TaskModuleIds.REGION_ID);
    }

    static RegionDefinition region(
            TaskExecutionContext context,
            TaskStep step,
            String key
    ) {
        UUID id = UUID.fromString(
                step.parameters().get(key)
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

    static boolean booleanParameter(
            TaskStep step,
            String key,
            boolean fallback
    ) {
        String value = step.parameters().get(key);
        return value == null || value.isBlank()
                ? fallback
                : Boolean.parseBoolean(value.trim());
    }

    static Set<String> itemFilter(TaskStep step) {
        return csvParameter(step, TaskModuleIds.ITEM_FILTER);
    }

    static Set<String> containerTypes(TaskStep step) {
        return csvParameter(step, TaskModuleIds.CONTAINER_TYPES);
    }

    static Set<CategoryMarkerSource> optionalMarkerSources(TaskStep step) {
        String value = step.parameters().get(TaskModuleIds.MARKER_SOURCES);
        if (value == null || value.isBlank()) {
            return null;
        }
        Set<CategoryMarkerSource> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                        "markerSources contains an empty source"
                );
            }
            result.add(CategoryMarkerSource.valueOf(trimmed));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "markerSources must contain at least one source"
            );
        }
        return Set.copyOf(result);
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
