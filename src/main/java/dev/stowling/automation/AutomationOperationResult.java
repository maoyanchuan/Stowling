package dev.stowling.automation;

import java.util.Objects;
import java.util.Optional;

public record AutomationOperationResult(
        AutomationOperationStatus status,
        Optional<String> message,
        Optional<String> lastConfirmedAction
) {
    public AutomationOperationResult {
        status = Objects.requireNonNull(status, "status");
        message = Objects.requireNonNull(message, "message");
        lastConfirmedAction = Objects.requireNonNull(
                lastConfirmedAction,
                "lastConfirmedAction"
        );
        if (status == AutomationOperationStatus.SUCCEEDED
                && lastConfirmedAction.isEmpty()) {
            throw new IllegalArgumentException(
                    "successful operation must confirm an action"
            );
        }
        if (status != AutomationOperationStatus.SUCCEEDED
                && lastConfirmedAction.isPresent()) {
            throw new IllegalArgumentException(
                    "unconfirmed operation must not report an action"
            );
        }
    }

    public static AutomationOperationResult running() {
        return new AutomationOperationResult(
                AutomationOperationStatus.RUNNING,
                Optional.empty(),
                Optional.empty()
        );
    }

    public static AutomationOperationResult running(String message) {
        return new AutomationOperationResult(
                AutomationOperationStatus.RUNNING,
                Optional.of(requireText(message, "message")),
                Optional.empty()
        );
    }

    public static AutomationOperationResult succeeded(String confirmedAction) {
        return new AutomationOperationResult(
                AutomationOperationStatus.SUCCEEDED,
                Optional.empty(),
                Optional.of(requireText(confirmedAction, "confirmedAction"))
        );
    }

    public static AutomationOperationResult paused(String reason) {
        return new AutomationOperationResult(
                AutomationOperationStatus.PAUSED,
                Optional.of(requireText(reason, "reason")),
                Optional.empty()
        );
    }

    public static AutomationOperationResult failed(String reason) {
        return new AutomationOperationResult(
                AutomationOperationStatus.FAILED,
                Optional.of(requireText(reason, "reason")),
                Optional.empty()
        );
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
