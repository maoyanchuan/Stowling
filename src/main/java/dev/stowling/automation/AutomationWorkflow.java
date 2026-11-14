package dev.stowling.automation;

import java.util.List;
import java.util.Objects;

public record AutomationWorkflow(
        String id,
        String name,
        List<AutomationStep> steps
) {
    public AutomationWorkflow {
        id = requireText(id, "automation workflow id");
        name = requireText(name, "automation workflow name");
        steps = List.copyOf(Objects.requireNonNull(steps, "automation steps"));
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
