package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.util.List;

public record TaskUpdateRequest(
        String revision,
        String name,
        TaskPriority priority,
        boolean repeatWhenIdle,
        List<TaskStepRequest> steps,
        TaskTriggerDefinition trigger
) {
    public TaskUpdateRequest {
        steps = List.copyOf(steps);
    }

    public TaskUpdateRequest(
            String revision,
            String name,
            List<TaskStepRequest> steps
    ) {
        this(
                revision,
                name,
                TaskPriority.NORMAL,
                false,
                steps,
                null
        );
    }

    public TaskUpdateRequest(
            String revision,
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            List<TaskStepRequest> steps
    ) {
        this(
                revision,
                name,
                priority,
                repeatWhenIdle,
                steps,
                null
        );
    }
}
