package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;

public record ContainerWithdrawalResult(
        ContainerTransferState state,
        String message,
        Map<String, Integer> withdrawn,
        boolean matchingItemsRemain
) {
    public ContainerWithdrawalResult {
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
        withdrawn = Map.copyOf(Objects.requireNonNull(withdrawn, "withdrawn"));
    }

    public static ContainerWithdrawalResult pending(String message) {
        return new ContainerWithdrawalResult(
                ContainerTransferState.PENDING,
                message,
                Map.of(),
                false
        );
    }

    public static ContainerWithdrawalResult succeeded(
            String message,
            Map<String, Integer> withdrawn,
            boolean matchingItemsRemain
    ) {
        return new ContainerWithdrawalResult(
                ContainerTransferState.SUCCEEDED,
                message,
                withdrawn,
                matchingItemsRemain
        );
    }

    public static ContainerWithdrawalResult failed(
            String message,
            Map<String, Integer> withdrawn,
            boolean matchingItemsRemain
    ) {
        return new ContainerWithdrawalResult(
                ContainerTransferState.FAILED,
                message,
                withdrawn,
                matchingItemsRemain
        );
    }
}
