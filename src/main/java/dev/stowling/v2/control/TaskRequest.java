package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.util.List;

public record TaskRequest(
        String name,
        TaskPriority priority,
        boolean repeatWhenIdle,
        List<TaskStepRequest> steps,
        TaskTriggerDefinition trigger
) {
    public TaskRequest {
        steps = List.copyOf(steps);
    }

    public TaskRequest(String name, List<TaskStepRequest> steps) {
        this(name, TaskPriority.NORMAL, false, steps, null);
    }

    public TaskRequest(
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            List<TaskStepRequest> steps
    ) {
        this(name, priority, repeatWhenIdle, steps, null);
    }
}
