package dev.stowling.v2.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TaskStep(
        UUID id,
        TaskStepKind kind,
        String moduleId,
        Map<String, String> parameters,
        List<TaskStep> steps,
        List<TaskStep> otherwise
) {
    public TaskStep {
        id = Objects.requireNonNull(id, "id");
        kind = kind == null ? TaskStepKind.ACTION : kind;
        moduleId = moduleId == null ? "" : moduleId.trim();
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        steps = steps == null ? List.of() : List.copyOf(steps);
        otherwise = otherwise == null ? List.of() : List.copyOf(otherwise);
        if (kind == TaskStepKind.ACTION && moduleId.isEmpty()) {
            throw new IllegalArgumentException("action steps require a moduleId");
        }
        if (kind != TaskStepKind.ACTION && !moduleId.isEmpty()) {
            throw new IllegalArgumentException(
                    "only action steps may define a moduleId"
            );
        }
    }

    public TaskStep(
            UUID id,
            String moduleId,
            Map<String, String> parameters
    ) {
        this(id, TaskStepKind.ACTION, moduleId, parameters, List.of(), List.of());
    }

    public static TaskStep action(
            UUID id,
            String moduleId,
            Map<String, String> parameters
    ) {
        return new TaskStep(id, TaskStepKind.ACTION, moduleId, parameters,
                List.of(), List.of());
    }

    public static TaskStep conditional(
            UUID id,
            Map<String, String> parameters,
            List<TaskStep> steps,
            List<TaskStep> otherwise
    ) {
        return new TaskStep(id, TaskStepKind.IF, "", parameters, steps, otherwise);
    }

    public static TaskStep repeat(
            UUID id,
            Map<String, String> parameters,
            List<TaskStep> steps
    ) {
        return new TaskStep(id, TaskStepKind.REPEAT, "", parameters, steps,
                List.of());
    }

    public static TaskStep createTask(
            UUID id,
            Map<String, String> parameters
    ) {
        return new TaskStep(id, TaskStepKind.CREATE_TASK, "", parameters,
                List.of(), List.of());
    }
}
