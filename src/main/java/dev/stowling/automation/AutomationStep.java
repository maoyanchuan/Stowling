package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AutomationStep(
        String id,
        AutomationStepType type,
        Map<String, String> parameters
) {
    public AutomationStep {
        id = requireText(id, "automation step id");
        type = Objects.requireNonNull(type, "automation step type");
        parameters = copyParameters(parameters);
    }

    public String parameter(String key) {
        return parameters.get(Objects.requireNonNull(key, "key"));
    }

    private static Map<String, String> copyParameters(Map<String, String> values) {
        Objects.requireNonNull(values, "automation step parameters");
        Map<String, String> copied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            copied.put(
                    requireText(entry.getKey(), "automation parameter name"),
                    requireText(entry.getValue(), "automation parameter value")
            );
        }
        return Map.copyOf(copied);
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
