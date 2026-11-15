package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;

public record ModuleParameter(
        String key,
        String label,
        String kind,
        boolean required,
        List<String> options,
        Integer min,
        Integer max,
        Integer step,
        String defaultValue
) {
    public ModuleParameter {
        key = requireText(key, "key");
        label = requireText(label, "label");
        kind = requireText(kind, "kind");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
    }

    public ModuleParameter(String key, String label, String kind, boolean required) {
        this(key, label, kind, required, List.of(), null, null, null, null);
    }

    public ModuleParameter(
            String key,
            String label,
            String kind,
            boolean required,
            List<String> options
    ) {
        this(key, label, kind, required, options, null, null, null, null);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
