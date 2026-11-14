package dev.stowling.automation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AutomationModuleDescriptor(
        AutomationStepType type,
        String displayName,
        String description,
        List<Parameter> parameters
) {
    public AutomationModuleDescriptor {
        type = Objects.requireNonNull(type, "module type");
        displayName = requireText(displayName, "displayName");
        description = Objects.requireNonNull(description, "description").trim();
        parameters = validateParameters(parameters);
    }

    public AutomationModuleDescriptor(AutomationStepType type, String displayName) {
        this(type, displayName, "", List.of());
    }

    public AutomationModuleDescriptor(
            AutomationStepType type,
            String displayName,
            String description
    ) {
        this(type, displayName, description, List.of());
    }

    public record Parameter(
            String key,
            String label,
            String kind,
            boolean required
    ) {
        public Parameter {
            key = requireText(key, "parameter key");
            label = requireText(label, "parameter label");
            kind = requireText(kind, "parameter kind");
        }
    }

    private static List<Parameter> validateParameters(List<Parameter> values) {
        List<Parameter> copied = List.copyOf(Objects.requireNonNull(
                values,
                "parameters"
        ));
        Set<String> keys = new HashSet<>();
        for (Parameter parameter : copied) {
            if (!keys.add(parameter.key())) {
                throw new IllegalArgumentException(
                        "parameter key is duplicated: " + parameter.key()
                );
            }
        }
        return copied;
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
