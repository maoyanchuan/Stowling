package dev.stowling.v2.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TaskDefinition(
        UUID id,
        String name,
        List<TaskStep> steps
) {
    public TaskDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.size() > 128) {
            throw new IllegalArgumentException("tasks require 1 to 128 steps");
        }
        Set<UUID> stepIds = new HashSet<>();
        for (TaskStep step : steps) {
            if (!stepIds.add(step.id())) {
                throw new IllegalArgumentException("step ids must be unique");
            }
        }
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException("name must contain 1 to 80 characters");
        }
        return trimmed;
    }
}
