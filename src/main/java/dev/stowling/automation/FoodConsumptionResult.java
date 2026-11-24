package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;

public record FoodConsumptionResult(
        ContainerTransferState state,
        String message,
        Map<String, Integer> items,
        java.util.Set<Integer> acquiredSlots
) {
    public FoodConsumptionResult(ContainerTransferState state, String message,
            Map<String, Integer> items) {
        this(state, message, items, java.util.Set.of());
    }

    public FoodConsumptionResult {
        acquiredSlots = java.util.Set.copyOf(acquiredSlots);
        if (acquiredSlots.stream().anyMatch(index -> index < 0 || index >= 36)) {
            throw new IllegalArgumentException("食物落点必须为主背包或快捷栏索引");
        }
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
        items = Map.copyOf(Objects.requireNonNull(items, "items"));
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException(
                        "food result items must be positive"
                );
            }
        }
    }

    public FoodConsumptionResult(
            ContainerTransferState state,
            String message
    ) {
        this(state, message, Map.of());
    }

    public static FoodConsumptionResult pending(String message) {
        return new FoodConsumptionResult(
                ContainerTransferState.PENDING,
                message,
                Map.of()
        );
    }

    public static FoodConsumptionResult succeeded(String message) {
        return succeeded(message, Map.of());
    }

    public static FoodConsumptionResult succeeded(
            String message,
            Map<String, Integer> items
    ) {
        return new FoodConsumptionResult(
                ContainerTransferState.SUCCEEDED,
                message,
                items
        );
    }

    public static FoodConsumptionResult failed(String message) {
        return failed(message, Map.of());
    }

    public static FoodConsumptionResult failed(
            String message,
            Map<String, Integer> items
    ) {
        return new FoodConsumptionResult(
                ContainerTransferState.FAILED,
                message,
                items
        );
    }
}
