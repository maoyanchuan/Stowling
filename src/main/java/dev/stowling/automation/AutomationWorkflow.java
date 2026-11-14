package dev.stowling.automation;

import dev.stowling.schedule.TaskSettings;
import java.util.List;
import java.util.Objects;

public record AutomationWorkflow(
        String id,
        String name,
        List<AutomationStep> steps,
        TaskSettings settings
) {
    public AutomationWorkflow {
        id = requireText(id, "automation workflow id");
        name = requireText(name, "automation workflow name");
        steps = List.copyOf(Objects.requireNonNull(steps, "automation steps"));
        settings = Objects.requireNonNull(settings, "settings");
    }

    public AutomationWorkflow(
            String id,
            String name,
            List<AutomationStep> steps
    ) {
        this(id, name, steps, TaskSettings.defaults());
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
