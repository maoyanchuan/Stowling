package dev.stowling.automation;

import java.util.Objects;

public record FoodConsumptionResult(
        ContainerTransferState state,
        String message
) {
    public FoodConsumptionResult {
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
    }

    public static FoodConsumptionResult pending(String message) {
        return new FoodConsumptionResult(
                ContainerTransferState.PENDING,
                message
        );
    }

    public static FoodConsumptionResult succeeded(String message) {
        return new FoodConsumptionResult(
                ContainerTransferState.SUCCEEDED,
                message
        );
    }

    public static FoodConsumptionResult failed(String message) {
        return new FoodConsumptionResult(
                ContainerTransferState.FAILED,
                message
        );
    }
}
