package dev.stowling.v2.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TaskStep(
        UUID id,
        String moduleId,
        Map<String, String> parameters
) {
    public TaskStep {
        id = Objects.requireNonNull(id, "id");
        moduleId = Objects.requireNonNull(moduleId, "moduleId").trim();
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        if (moduleId.isEmpty()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
    }
}
