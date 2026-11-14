package dev.stowling.automation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AutomationModuleDescriptor(
        String moduleId,
        AutomationStepType type,
        String displayName,
        String description,
        List<Parameter> parameters
) {
    public AutomationModuleDescriptor {
        moduleId = requireText(moduleId, "moduleId");
        type = Objects.requireNonNull(type, "module type");
        displayName = requireText(displayName, "displayName");
        description = Objects.requireNonNull(description, "description").trim();
        parameters = validateParameters(parameters);
    }

    public AutomationModuleDescriptor(
            AutomationStepType type,
            String displayName,
            String description,
            List<Parameter> parameters
    ) {
        this(type.name(), type, displayName, description, parameters);
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

    public List<String> validate(Map<String, String> values) {
        Objects.requireNonNull(values, "parameters");
        List<String> errors = new java.util.ArrayList<>();
        for (Parameter parameter : parameters) {
            String value = value(parameter, values);
            if (value == null || value.isBlank()) {
                if (parameter.required()) {
                    errors.add("missing required parameter: " + parameter.key());
                }
                continue;
            }
            if ("positive-integer".equals(parameter.kind())) {
                validatePositiveInteger(parameter.key(), value.trim(), errors);
            }
        }
        return List.copyOf(errors);
    }

    public void requireValid(Map<String, String> values) {
        List<String> errors = validate(values);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    public record Parameter(
            String key,
            String label,
            String kind,
            boolean required,
            List<String> aliases
    ) {
        public Parameter {
            key = requireText(key, "parameter key");
            label = requireText(label, "parameter label");
            kind = requireText(kind, "parameter kind");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
            Set<String> names = new HashSet<>();
            names.add(key);
            for (String alias : aliases) {
                String checkedAlias = requireText(alias, "parameter alias");
                if (!names.add(checkedAlias)) {
                    throw new IllegalArgumentException(
                            "parameter alias is duplicated: " + checkedAlias
                    );
                }
            }
        }

        public Parameter(
                String key,
                String label,
                String kind,
                boolean required
        ) {
            this(key, label, kind, required, List.of());
        }
    }

    private static List<Parameter> validateParameters(List<Parameter> values) {
        List<Parameter> copied = List.copyOf(Objects.requireNonNull(
                values,
                "parameters"
        ));
        Set<String> keys = new HashSet<>();
        for (Parameter parameter : copied) {
            for (String name : parameterNames(parameter)) {
                if (!keys.add(name)) {
                    throw new IllegalArgumentException(
                            "parameter name is duplicated: " + name
                    );
                }
            }
        }
        return copied;
    }

    private static List<String> parameterNames(Parameter parameter) {
        List<String> names = new java.util.ArrayList<>();
        names.add(parameter.key());
        names.addAll(parameter.aliases());
        return names;
    }

    private static String value(
            Parameter parameter,
            Map<String, String> values
    ) {
        String value = values.get(parameter.key());
        if (value != null) {
            return value;
        }
        for (String alias : parameter.aliases()) {
            value = values.get(alias);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void validatePositiveInteger(
            String key,
            String value,
            List<String> errors
    ) {
        try {
            if (Integer.parseInt(value) < 1) {
                errors.add(key + " must be a positive integer");
            }
        } catch (NumberFormatException exception) {
            errors.add(key + " must be a positive integer");
        }
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
