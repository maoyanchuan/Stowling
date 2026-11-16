package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import java.util.List;
import java.util.UUID;

public record TaskUpdateRequest(
        String revision,
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
                false,
                null
        );
    }

    public TaskUpdateRequest(
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
        this(
                revision,
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

    public TaskUpdateRequest(
            String revision,
            String name,
            List<TaskStepRequest> steps
    ) {
        this(revision, name, TaskPriority.NORMAL, false, steps);
    }
}
