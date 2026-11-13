package dev.stowling.automation;

import java.util.Objects;

public record AutomationPauseReason(String code, String detail) {
    public AutomationPauseReason {
        code = requireText(code, "code");
        detail = requireText(detail, "detail");
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
