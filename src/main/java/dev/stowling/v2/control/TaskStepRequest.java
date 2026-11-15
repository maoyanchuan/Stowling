package dev.stowling.v2.control;

import java.util.Map;

public record TaskStepRequest(
        String id,
        String moduleId,
        Map<String, String> parameters
) {
    public TaskStepRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
