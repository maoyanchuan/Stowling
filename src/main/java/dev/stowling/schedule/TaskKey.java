package dev.stowling.schedule;

import java.util.Objects;

public record TaskKey(String kind, String id) {
    public static final String PICKUP = "PICKUP";
    public static final String AUTOMATION = "AUTOMATION";

    public TaskKey {
        kind = requireKind(kind);
        id = requireText(id, "task id");
    }

    private static String requireKind(String kind) {
        String value = requireText(kind, "task kind");
        if (!PICKUP.equals(value) && !AUTOMATION.equals(value)) {
            throw new IllegalArgumentException("unsupported task kind: " + value);
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
