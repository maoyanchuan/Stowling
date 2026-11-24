package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;

public record ContainerTransferResult(
        ContainerTransferState state,
        String message,
        Map<String, Integer> remaining,
        Map<String, Integer> transferred
) {
    public ContainerTransferResult(ContainerTransferState state, String message,
            Map<String, Integer> remaining) {
        this(state, message, remaining, Map.of());
    }

    public ContainerTransferResult {
        transferred = Map.copyOf(transferred);
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
        remaining = Map.copyOf(Objects.requireNonNull(remaining, "remaining"));
    }

    public static ContainerTransferResult pending(
            String message,
            Map<String, Integer> remaining
    ) {
        return new ContainerTransferResult(
                ContainerTransferState.PENDING,
                message,
                remaining
        );
    }

    public static ContainerTransferResult succeeded(
            String message,
            Map<String, Integer> remaining
    ) {
        return new ContainerTransferResult(
                ContainerTransferState.SUCCEEDED,
                message,
                remaining
        );
    }

    public static ContainerTransferResult failed(
            String message,
            Map<String, Integer> remaining
    ) {
        return new ContainerTransferResult(
                ContainerTransferState.FAILED,
                message,
                remaining
        );
    }
}
