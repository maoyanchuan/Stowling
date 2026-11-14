package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AutomationModuleContext(
        UUID taskId,
        AutomationStep step,
        Map<String, String> moduleState,
        boolean recovered,
        Optional<String> lastConfirmedAction
) {
    public AutomationModuleContext {
        taskId = Objects.requireNonNull(taskId, "taskId");
        step = Objects.requireNonNull(step, "step");
        moduleState = Map.copyOf(Objects.requireNonNull(moduleState, "moduleState"));
        lastConfirmedAction = Objects.requireNonNull(
                lastConfirmedAction,
                "lastConfirmedAction"
        );
    }
}
