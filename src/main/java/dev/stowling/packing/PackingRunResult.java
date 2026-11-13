package dev.stowling.packing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PackingRunResult(
        PackingRunStatus status,
        String taskId,
        String itemId,
        int requestedQuantity,
        int insertedQuantity,
        int remainingQuantity,
        List<PackingPartialBox> partialBoxes,
        Optional<PackedBoxTransferIntent> transferIntent,
        Optional<OverflowIntent> overflowIntent,
        List<PackingStationFailure> stationFailures
) {
    public PackingRunResult {
        status = Objects.requireNonNull(status, "status");
        taskId = PackingChecks.requireText(taskId, "taskId");
        itemId = PackingChecks.requireText(itemId, "itemId");
        if (requestedQuantity < 1) {
            throw new IllegalArgumentException("requestedQuantity must be positive");
        }
        if (insertedQuantity < 0 || insertedQuantity > requestedQuantity) {
            throw new IllegalArgumentException(
                    "insertedQuantity must be between zero and requestedQuantity"
            );
        }
        if (remainingQuantity != requestedQuantity - insertedQuantity) {
            throw new IllegalArgumentException(
                    "remainingQuantity must be requestedQuantity minus insertedQuantity"
            );
        }
        partialBoxes = List.copyOf(Objects.requireNonNull(partialBoxes, "partialBoxes"));
        transferIntent = Objects.requireNonNull(transferIntent, "transferIntent");
        overflowIntent = Objects.requireNonNull(overflowIntent, "overflowIntent");
        stationFailures = List.copyOf(
                Objects.requireNonNull(stationFailures, "stationFailures")
        );
        validateStatus(
                status,
                insertedQuantity,
                remainingQuantity,
                partialBoxes,
                transferIntent,
                overflowIntent,
                stationFailures
        );
    }

    private static void validateStatus(
            PackingRunStatus status,
            int insertedQuantity,
            int remainingQuantity,
            List<PackingPartialBox> partialBoxes,
            Optional<PackedBoxTransferIntent> transferIntent,
            Optional<OverflowIntent> overflowIntent,
            List<PackingStationFailure> stationFailures
    ) {
        switch (status) {
            case FULL_BOX_READY -> {
                if (transferIntent.isEmpty() || overflowIntent.isPresent()) {
                    throw new IllegalArgumentException(
                            "full-box result requires a transfer and no overflow"
                    );
                }
            }
            case PARTIAL_BOX -> {
                if (transferIntent.isPresent() || overflowIntent.isPresent()) {
                    throw new IllegalArgumentException(
                            "partial result cannot contain transfer or overflow intents"
                    );
                }
                if (remainingQuantity != 0 || partialBoxes.isEmpty()) {
                    throw new IllegalArgumentException(
                            "partial result requires all items placed in a partial box"
                    );
                }
            }
            case FAILED_RETAINED -> {
                if (transferIntent.isPresent()
                        || overflowIntent.isPresent()
                        || remainingQuantity != 0
                        || insertedQuantity == 0
                        || stationFailures.isEmpty()) {
                    throw new IllegalArgumentException(
                            "retained failure requires inserted items and failures only"
                    );
                }
            }
            case OVERFLOW -> {
                if (transferIntent.isPresent() || overflowIntent.isEmpty()) {
                    throw new IllegalArgumentException(
                            "overflow result requires an overflow and no transfer"
                    );
                }
                if (remainingQuantity < 1
                        || overflowIntent.get().quantity() != remainingQuantity) {
                    throw new IllegalArgumentException(
                            "overflow quantity must equal the remaining quantity"
                    );
                }
            }
        }
    }
}
