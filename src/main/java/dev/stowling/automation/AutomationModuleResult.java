package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AutomationModuleResult(
        AutomationModuleStatus status,
        Map<String, String> moduleState,
        Optional<String> lastConfirmedAction,
        Optional<String> message
) {
    public AutomationModuleResult {
        status = Objects.requireNonNull(status, "status");
        moduleState = Map.copyOf(Objects.requireNonNull(moduleState, "moduleState"));
        lastConfirmedAction = Objects.requireNonNull(
                lastConfirmedAction,
                "lastConfirmedAction"
        );
        message = Objects.requireNonNull(message, "message");
    }

    public static AutomationModuleResult running(Map<String, String> state) {
        return new AutomationModuleResult(
                AutomationModuleStatus.RUNNING,
                state,
                Optional.empty(),
                Optional.empty()
        );
    }

    public static AutomationModuleResult succeeded(
            Map<String, String> state,
            String confirmedAction
    ) {
        return new AutomationModuleResult(
                AutomationModuleStatus.SUCCEEDED,
                state,
                Optional.of(confirmedAction),
                Optional.empty()
        );
    }

    public static AutomationModuleResult paused(String reason) {
        return new AutomationModuleResult(
                AutomationModuleStatus.PAUSED,
                Map.of(),
                Optional.empty(),
                Optional.of(reason)
        );
    }

    public static AutomationModuleResult failed(String reason) {
        return new AutomationModuleResult(
                AutomationModuleStatus.FAILED,
                Map.of(),
                Optional.empty(),
                Optional.of(reason)
        );
    }
}
