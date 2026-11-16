package dev.stowling.v2.model;

import java.util.Locale;
import java.util.Objects;

public enum TaskCondition {
    ALWAYS,
    FOOD_BELOW_THRESHOLD,
    HAS_CONTAINERS,
    HAS_CARRIED_ITEMS,
    HAS_MANAGED_ITEMS,
    CACHE_HAS_MORE,
    HAS_MARKER_CONTAINERS;

    public static TaskCondition parse(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "unknown task condition: " + value,
                    exception
            );
        }
    }
}
