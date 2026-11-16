package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import java.util.List;
import java.util.UUID;

public record TaskRequest(
        String name,
        TaskPriority priority,
        boolean repeatWhenIdle,
        List<TaskStepRequest> steps,
        TaskInterruptionMode interruptionMode,
        String interruptionTaskRef,
        boolean defaultInterruptionFlow,
        boolean requeueAfterInterruption,
        UUID folderId
) {
    public TaskRequest {
        steps = List.copyOf(steps);
    }

    public TaskRequest(
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            List<TaskStepRequest> steps
    ) {
        this(
                name,
                priority,
                repeatWhenIdle,
                steps,
                TaskInterruptionMode.DEFAULT,
                null,
                false,
                false,
                null
        );
    }

    public TaskRequest(
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            List<TaskStepRequest> steps,
            TaskInterruptionMode interruptionMode,
            String interruptionTaskRef,
            boolean defaultInterruptionFlow,
            boolean requeueAfterInterruption
    ) {
        this(
                name,
                priority,
                repeatWhenIdle,
                steps,
                interruptionMode,
                interruptionTaskRef,
                defaultInterruptionFlow,
                requeueAfterInterruption,
                null
        );
    }

    public TaskRequest(String name, List<TaskStepRequest> steps) {
        this(name, TaskPriority.NORMAL, false, steps);
    }
}
