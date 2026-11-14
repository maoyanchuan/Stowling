package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationStep;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

final class ModuleParameters {
    private ModuleParameters() {
    }

    static AutomationModuleResult guard(
            Supplier<AutomationModuleResult> action
    ) {
        try {
            return Objects.requireNonNull(action.get(), "module result");
        } catch (IllegalArgumentException exception) {
            return AutomationModuleResult.failed(exception.getMessage());
        }
    }

    static String required(AutomationStep step, String key) {
        return requireText(step.parameter(key), key);
    }

    static String firstRequired(AutomationStep step, String... keys) {
        for (String key : keys) {
            String value = step.parameter(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException(
                "missing required parameter: " + String.join(" or ", keys)
        );
    }

    static Optional<String> optional(AutomationStep step, String key) {
        String value = step.parameter(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    static int positiveInt(AutomationStep step, String key) {
        String value = required(step, key);
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    key + " must be a positive integer"
            );
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(
                    key + " must be a positive integer"
            );
        }
        return parsed;
    }

    static long positiveLong(AutomationStep step, String key) {
        String value = required(step, key);
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    key + " must be a positive integer"
            );
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(
                    key + " must be a positive integer"
            );
        }
        return parsed;
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
