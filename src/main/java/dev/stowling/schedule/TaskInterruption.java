package dev.stowling.schedule;

import dev.stowling.automation.AutomationStep;
import java.util.List;
import java.util.Objects;

public record TaskInterruption(
        InterruptionKind kind,
        List<AutomationStep> steps,
        String taskId
) {
    public TaskInterruption {
        kind = Objects.requireNonNull(kind, "interruption kind");
        steps = List.copyOf(Objects.requireNonNull(steps, "interruption steps"));
        taskId = normalizeTaskId(taskId);
    }

    public static TaskInterruption none() {
        return new TaskInterruption(InterruptionKind.NONE, List.of(), null);
    }

    private static String normalizeTaskId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
