package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskStepKind;
import java.util.List;
import java.util.Map;

public record TaskStepRequest(
        String id,
        TaskStepKind kind,
        String moduleId,
        Map<String, String> parameters,
        List<TaskStepRequest> steps,
        List<TaskStepRequest> otherwise
) {
    public TaskStepRequest {
        kind = kind == null ? TaskStepKind.ACTION : kind;
        moduleId = moduleId == null ? "" : moduleId.trim();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        steps = steps == null ? List.of() : List.copyOf(steps);
        otherwise = otherwise == null ? List.of() : List.copyOf(otherwise);
    }

    public TaskStepRequest(
            String id,
            String moduleId,
            Map<String, String> parameters
    ) {
        this(id, TaskStepKind.ACTION, moduleId, parameters, List.of(), List.of());
    }
}
