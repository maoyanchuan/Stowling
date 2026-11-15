package dev.stowling.v2.model;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TaskTriggerDefinition(
        TaskTriggerType type,
        UUID coordinateId,
        String itemFilter,
        int intervalTicks,
        int cooldownTicks
) {
    public static final int DEFAULT_INTERVAL_TICKS = 20;
    public static final int DEFAULT_COOLDOWN_TICKS = 100;
    public static final int MAX_INTERVAL_TICKS = 1_200;
    public static final int MAX_COOLDOWN_TICKS = 72_000;
    private static final int MAX_ITEM_FILTER_LENGTH = 2_048;

    public TaskTriggerDefinition {
        type = Objects.requireNonNull(type, "type");
        coordinateId = Objects.requireNonNull(coordinateId, "coordinateId");
        itemFilter = normalizeItemFilter(itemFilter);
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
    }

    public static TaskTriggerDefinition droppedItems(
            UUID coordinateId,
            String itemFilter,
            int intervalTicks,
            int cooldownTicks
    ) {
        return new TaskTriggerDefinition(
                TaskTriggerType.DROPPED_ITEMS,
                coordinateId,
                itemFilter,
                intervalTicks,
                cooldownTicks
        );
    }

    public Set<String> itemIds() {
        if (itemFilter == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String part : itemFilter.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Set.copyOf(result);
    }

    public boolean matches(String itemId) {
        Set<String> filter = itemIds();
        if (filter.isEmpty()) {
            return true;
        }
        int separator = itemId.indexOf(':');
        String path = separator < 0
                ? itemId
                : itemId.substring(separator + 1);
        return filter.contains(itemId) || filter.contains(path);
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
