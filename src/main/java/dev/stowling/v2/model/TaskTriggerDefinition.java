package dev.stowling.v2.model;

import java.util.Objects;
import java.util.UUID;

public record TaskTriggerDefinition(
        UUID id,
        String name,
        TaskTriggerType type,
        UUID targetTaskId,
        TaskPriority priority,
        int intervalTicks,
        int cooldownTicks,
        UUID coordinateId,
        String itemFilter,
        Integer foodLevelThreshold,
        UUID cacheRegionId,
        Integer radius,
        Boolean disabled,
        String requiredProtectedFood,
        TaskSchedule schedule
) {
    public static final int DEFAULT_INTERVAL_TICKS = 20;
    public static final int DEFAULT_COOLDOWN_TICKS = 100;
    public static final int MAX_INTERVAL_TICKS = 1_200;
    public static final int MAX_COOLDOWN_TICKS = 72_000;
    private static final int MAX_ITEM_FILTER_LENGTH = 2_048;

    public TaskTriggerDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        type = Objects.requireNonNull(type, "type");
        targetTaskId = Objects.requireNonNull(
                targetTaskId,
                "targetTaskId"
        );
        priority = priority == null ? TaskPriority.NORMAL : priority;
        itemFilter = normalizeItemFilter(itemFilter);
        if (requiredProtectedFood != null && (type != TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW
                || !requiredProtectedFood.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
            throw new IllegalArgumentException("requiredProtectedFood must identify a food item");
        }
        if (intervalTicks < 1 || intervalTicks > MAX_INTERVAL_TICKS) {
            throw new IllegalArgumentException(
                    "intervalTicks must be between 1 and "
                            + MAX_INTERVAL_TICKS
            );
        }
        if (cooldownTicks < 0 || cooldownTicks > MAX_COOLDOWN_TICKS) {
            throw new IllegalArgumentException(
                    "cooldownTicks must be between 0 and "
                            + MAX_COOLDOWN_TICKS
            );
        }
        if (type == TaskTriggerType.SCHEDULED) {
            if (schedule == null || schedule.mode() == TaskSchedule.Mode.MANUAL
                    || schedule.mode() == TaskSchedule.Mode.INTERVAL
                            && schedule.anchorEpochSecond() == null
                    || coordinateId != null || itemFilter != null || foodLevelThreshold != null
                    || cacheRegionId != null || radius != null) {
                throw new IllegalArgumentException("定时触发器必须包含有效的时间规则");
            }
        } else if (schedule != null) {
            throw new IllegalArgumentException("只有定时触发器可以设置时间规则");
        }
        if (type == TaskTriggerType.DROPPED_ITEMS) {
            if (coordinateId == null
                    || foodLevelThreshold != null
                    || cacheRegionId != null
                    || (radius != null && (radius < 0 || radius > 128))) {
                throw new IllegalArgumentException(
                        "dropped item triggers require a coordinate and valid radius"
                );
            }
        } else if (type == TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW) {
            if (coordinateId != null
                    || itemFilter != null
                    || foodLevelThreshold == null
                    || foodLevelThreshold < 0
                    || foodLevelThreshold > 20
                    || cacheRegionId != null
                    || radius != null) {
                throw new IllegalArgumentException(
                        "food triggers require a threshold from 0 to 20"
                );
            }
        } else if (type == TaskTriggerType.CACHE_UPDATED) {
            if (cacheRegionId == null
                    || coordinateId != null
                    || itemFilter != null
                    || foodLevelThreshold != null
                    || radius != null) {
                throw new IllegalArgumentException(
                        "cache triggers require a cache region"
                );
            }
        }
    }

    public TaskTriggerDefinition(UUID id, String name, TaskTriggerType type, UUID targetTaskId,
            TaskPriority priority, int intervalTicks, int cooldownTicks, UUID coordinateId,
            String itemFilter, Integer foodLevelThreshold, UUID cacheRegionId, Integer radius,
            Boolean disabled, String requiredProtectedFood) {
        this(id, name, type, targetTaskId, priority, intervalTicks, cooldownTicks, coordinateId,
                itemFilter, foodLevelThreshold, cacheRegionId, radius, disabled,
                requiredProtectedFood, null);
    }

    public static TaskTriggerDefinition scheduled(UUID id, String name, UUID taskId,
            TaskSchedule schedule) {
        return new TaskTriggerDefinition(id, name, TaskTriggerType.SCHEDULED, taskId,
                TaskPriority.BACKGROUND, DEFAULT_INTERVAL_TICKS, 0, null, null, null, null,
                null, null, null, schedule);
    }

    public TaskTriggerDefinition(UUID id, String name, TaskTriggerType type, UUID targetTaskId,
            TaskPriority priority, int intervalTicks, int cooldownTicks, UUID coordinateId,
            String itemFilter, Integer foodLevelThreshold, UUID cacheRegionId, Integer radius) {
        this(id, name, type, targetTaskId, priority, intervalTicks, cooldownTicks, coordinateId,
                itemFilter, foodLevelThreshold, cacheRegionId, radius, null, null);
    }

    public TaskTriggerDefinition(
            UUID id,
            String name,
            TaskTriggerType type,
            UUID targetTaskId,
            TaskPriority priority,
            int intervalTicks,
            int cooldownTicks,
            UUID coordinateId,
            String itemFilter,
            Integer foodLevelThreshold,
            UUID cacheRegionId
    ) {
        this(
                id,
                name,
                type,
                targetTaskId,
                priority,
                intervalTicks,
                cooldownTicks,
                coordinateId,
                itemFilter,
                foodLevelThreshold,
                cacheRegionId,
                null
        );
    }

    public static TaskTriggerDefinition droppedItems(
            UUID id,
            String name,
            UUID targetTaskId,
            TaskPriority priority,
            int intervalTicks,
            int cooldownTicks,
            UUID coordinateId,
            String itemFilter
    ) {
        return new TaskTriggerDefinition(
                id,
                name,
                TaskTriggerType.DROPPED_ITEMS,
                targetTaskId,
                priority,
                intervalTicks,
                cooldownTicks,
                coordinateId,
                itemFilter,
                null,
                null,
                null
        );
    }

    public static TaskTriggerDefinition foodLevelAtOrBelow(
            UUID id,
            String name,
            UUID targetTaskId,
            TaskPriority priority,
            int intervalTicks,
            int cooldownTicks,
            int foodLevelThreshold
    ) {
        return new TaskTriggerDefinition(
                id,
                name,
                TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW,
                targetTaskId,
                priority,
                intervalTicks,
                cooldownTicks,
                null,
                null,
                foodLevelThreshold,
                null,
                null
        );
    }

    public static TaskTriggerDefinition cacheUpdated(
            UUID id,
            String name,
            UUID targetTaskId,
            TaskPriority priority,
            int intervalTicks,
            int cooldownTicks,
            UUID cacheRegionId
    ) {
        return new TaskTriggerDefinition(
                id,
                name,
                TaskTriggerType.CACHE_UPDATED,
                targetTaskId,
                priority,
                intervalTicks,
                cooldownTicks,
                null,
                null,
                null,
                cacheRegionId,
                null
        );
    }

    public boolean matches(String itemId) {
        if (itemFilter == null) {
            return true;
        }
        int separator = itemId.indexOf(':');
        String path = separator < 0
                ? itemId
                : itemId.substring(separator + 1);
        for (String part : itemFilter.split(",")) {
            String candidate = part.trim();
            if (candidate.equals(itemId) || candidate.equals(path)) {
                return true;
            }
        }
        return false;
    }

    public boolean enabled() {
        return !Boolean.TRUE.equals(disabled);
    }

    public TaskTriggerDefinition withEnabled(boolean enabled) {
        return new TaskTriggerDefinition(id, name, type, targetTaskId, priority, intervalTicks,
                cooldownTicks, coordinateId, itemFilter, foodLevelThreshold, cacheRegionId, radius,
                enabled ? null : true, requiredProtectedFood, schedule);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException(
                    "name must contain 1 to 80 characters"
            );
        }
        return trimmed;
    }

    private static String normalizeItemFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_ITEM_FILTER_LENGTH) {
            throw new IllegalArgumentException("itemFilter is too long");
        }
        return trimmed;
    }
}
