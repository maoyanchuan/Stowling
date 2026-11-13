package dev.stowling.domain;

import java.util.Objects;

final class DomainChecks {
    private DomainChecks() {
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
