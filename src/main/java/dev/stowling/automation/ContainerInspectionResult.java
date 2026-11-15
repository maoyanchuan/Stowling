package dev.stowling.automation;

import java.util.Objects;
import java.util.Set;

public record ContainerInspectionResult(
        ContainerTransferState state,
        Set<String> itemIds,
        String message
) {
    public ContainerInspectionResult {
        state = Objects.requireNonNull(state, "state");
        itemIds = Set.copyOf(Objects.requireNonNull(itemIds, "itemIds"));
        message = Objects.requireNonNull(message, "message");
    }

    public static ContainerInspectionResult pending(String message) {
        return new ContainerInspectionResult(
                ContainerTransferState.PENDING,
                Set.of(),
                message
        );
    }

    public static ContainerInspectionResult success(
            String message,
            Set<String> itemIds
    ) {
        return new ContainerInspectionResult(
                ContainerTransferState.SUCCEEDED,
                itemIds,
                message
        );
    }

    public static ContainerInspectionResult failed(String message) {
        return new ContainerInspectionResult(
                ContainerTransferState.FAILED,
                Set.of(),
                message
        );
    }
}
