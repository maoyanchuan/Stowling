package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import java.util.List;

public record TaskUpdateRequest(
        String revision,
        String name,
        TaskPriority priority,
        boolean repeatWhenIdle,
        List<TaskStepRequest> steps,
        TaskInterruptionMode interruptionMode,
        String interruptionTaskRef,
        boolean defaultInterruptionFlow,
        boolean requeueAfterInterruption
) {
    public TaskUpdateRequest {
        steps = List.copyOf(steps);
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
                TaskInterruptionMode.DEFAULT,
                null,
                false,
                false
        );
    }

    public TaskUpdateRequest(
            String revision,
            String name,
            List<TaskStepRequest> steps
    ) {
        this(revision, name, TaskPriority.NORMAL, false, steps);
    }
}
