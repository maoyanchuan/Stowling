package dev.stowling.packing;

import java.util.Objects;

final class PackingChecks {
    private PackingChecks() {
    }

    static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
