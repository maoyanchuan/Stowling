package dev.stowling.v2.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record TaskDefinition(
        UUID id,
        String name,
        TaskPriority priority,
        boolean repeatWhenIdle,
        List<TaskStep> steps,
        TaskInterruptionMode interruptionMode,
        String interruptionTaskRef,
        boolean defaultInterruptionFlow,
        boolean requeueAfterInterruption,
        UUID folderId
) {
    public static final int MAX_STEP_DEPTH = 16;
    public static final int MAX_TOTAL_STEPS = 512;

    public TaskDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        priority = priority == null ? TaskPriority.NORMAL : priority;
        interruptionMode = interruptionMode == null
                ? TaskInterruptionMode.DEFAULT
                : interruptionMode;
        interruptionTaskRef = normalizeInterruptionTaskRef(
                interruptionMode,
                interruptionTaskRef
        );
        if (repeatWhenIdle && priority != TaskPriority.BACKGROUND) {
            throw new IllegalArgumentException(
                    "only background tasks can repeat when idle"
            );
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.size() > 128) {
            throw new IllegalArgumentException("tasks require 1 to 128 steps");
        }
        StepCounter counter = new StepCounter();
        validateSteps(steps, 1, counter);
        if (defaultInterruptionFlow) {
            if (interruptionMode != TaskInterruptionMode.NONE) {
                throw new IllegalArgumentException(
                        "default interruption flows require NONE mode"
                );
            }
            if (repeatWhenIdle) {
                throw new IllegalArgumentException(
                        "default interruption flows cannot repeat when idle"
                );
            }
            if (containsCreateTask(steps)) {
                throw new IllegalArgumentException(
                        "default interruption flows cannot create tasks"
                );
            }
        }
    }

    public TaskDefinition(
            UUID id,
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            List<TaskStep> steps
    ) {
        this(
                id,
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

    public TaskDefinition(
            UUID id,
            String name,
            TaskPriority priority,
            boolean repeatWhenIdle,
            List<TaskStep> steps,
            TaskInterruptionMode interruptionMode,
            String interruptionTaskRef,
            boolean defaultInterruptionFlow,
            boolean requeueAfterInterruption
    ) {
        this(
                id,
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

    public TaskDefinition(
            UUID id,
            String name,
            List<TaskStep> steps
    ) {
        this(id, name, TaskPriority.NORMAL, false, steps);
    }

    public TaskDefinition(
            UUID id,
            String name,
            TaskPriority priority,
            List<TaskStep> steps
    ) {
        this(id, name, priority, false, steps);
    }

    private static void validateSteps(
            List<TaskStep> current,
            int depth,
            StepCounter counter
    ) {
        if (depth > MAX_STEP_DEPTH) {
            throw new IllegalArgumentException("task step nesting is too deep");
        }
        for (TaskStep step : current) {
            counter.add(step.id());
            validateSteps(step.steps(), depth + 1, counter);
            validateSteps(step.otherwise(), depth + 1, counter);
        }
    }

    private static boolean containsCreateTask(List<TaskStep> steps) {
        for (TaskStep step : steps) {
            if (step.kind() == TaskStepKind.CREATE_TASK
                    || containsCreateTask(step.steps())
                    || containsCreateTask(step.otherwise())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeInterruptionTaskRef(
            TaskInterruptionMode mode,
            String value
    ) {
        if (mode != TaskInterruptionMode.CUSTOM) {
            return null;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "custom interruption requires a task reference"
            );
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() != ResourceType.TASK) {
            throw new IllegalArgumentException(
                    "interruptionTaskRef must reference a task"
            );
        }
        return reference.encode();
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException(
                    "name must contain 1 to 80 characters"
            );
        }
        return trimmed;
    }

    private static final class StepCounter {
        private final Set<UUID> ids = new HashSet<>();
        private int total;

        private void add(UUID id) {
            if (!ids.add(id)) {
                throw new IllegalArgumentException("step ids must be unique");
            }
            total++;
            if (total > MAX_TOTAL_STEPS) {
                throw new IllegalArgumentException("task contains too many steps");
            }
        }
    }
}
