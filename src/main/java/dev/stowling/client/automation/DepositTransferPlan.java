package dev.stowling.client.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DepositTransferPlan {
    private static final int STABLE_OBSERVATIONS = 2;

    private final int requestedCount;
    private final int sourceCount;
    private final int moveCount;
    private final boolean quickMove;
    private final List<Placement> placements;

    private DepositTransferPlan(
            int requestedCount,
            int sourceCount,
            int moveCount,
            boolean quickMove,
            List<Placement> placements
    ) {
        this.requestedCount = requestedCount;
        this.sourceCount = sourceCount;
        this.moveCount = moveCount;
        this.quickMove = quickMove;
        this.placements = List.copyOf(placements);
    }

    static DepositTransferPlan create(
            int requestedCount,
            int sourceCount,
            List<Destination> destinations
    ) {
        Objects.requireNonNull(destinations, "destinations");
        if (requestedCount <= 0 || sourceCount <= 0) {
            throw new IllegalArgumentException("requested and source counts must be positive");
        }
        int availableToMove = Math.min(requestedCount, sourceCount);
        int allocated = 0;
        List<Placement> placements = new ArrayList<>();
        for (Destination destination : destinations) {
            int amount = Math.min(
                    availableToMove - allocated,
                    destination.capacity()
            );
            if (amount > 0) {
                placements.add(new Placement(destination.slot(), amount));
                allocated += amount;
            }
            if (allocated == availableToMove) {
                break;
            }
        }
        boolean quickMove = allocated == sourceCount;
        return new DepositTransferPlan(
                requestedCount,
                sourceCount,
                allocated,
                quickMove,
                placements
        );
    }

    int moveCount() {
        return moveCount;
    }

    int sourceRemainderAfterMove() {
        return sourceCount - moveCount;
    }

    int remainingAfterConfirmed() {
        return remainingAfterAccepted(moveCount);
    }

    int remainingAfterAccepted(int accepted) {
        if (accepted < 0 || accepted > moveCount) {
            throw new IllegalArgumentException("accepted count is outside the plan");
        }
        return requestedCount - accepted;
    }

    int remainingAfterUnconfirmed() {
        return requestedCount;
    }

    int remainingAfterFailure() {
        return requestedCount;
    }

    boolean hasMovement() {
        return moveCount > 0;
    }

    boolean usesQuickMove() {
        return quickMove;
    }

    List<Placement> placements() {
        return placements;
    }

    static TransferObservation observeConfirmation(
            int pendingCount,
            int previousDelta,
            int previousObservations,
            int playerDelta,
            int containerDelta
    ) {
        return observe(
                pendingCount,
                previousDelta,
                previousObservations,
                playerDelta,
                containerDelta,
                false
        );
    }

    static TransferObservation observeSettlement(
            int pendingCount,
            int previousDelta,
            int previousObservations,
            int playerDelta,
            int containerDelta
    ) {
        return observe(
                pendingCount,
                previousDelta,
                previousObservations,
                playerDelta,
                containerDelta,
                true
        );
    }

    static int outgoingCount(int playerBefore, int playerAfter, long pickupsBefore,
            long pickupsAfter) {
        if (pickupsAfter < pickupsBefore) {
            throw new IllegalStateException("拾取回执所属世界已改变");
        }
        return Math.toIntExact((long) playerBefore - playerAfter + pickupsAfter - pickupsBefore);
    }

    /** Only a newly opened, initialized menu may use the destination's authoritative delta. */
    static TransferObservation observeServerSnapshot(int pendingCount, int previousDelta,
            int previousObservations, int containerDelta) {
        if (pendingCount <= 0) {
            throw new IllegalArgumentException("pending count must be positive");
        }
        if (containerDelta == pendingCount) {
            return new TransferObservation(containerDelta, STABLE_OBSERVATIONS, true);
        }
        return observeSettlement(pendingCount, previousDelta, previousObservations,
                containerDelta, containerDelta);
    }

    static boolean needsServerSnapshot(int pendingCount, int outgoingCount, int containerDelta) {
        return containerDelta > 0 && containerDelta <= pendingCount
                && outgoingCount != containerDelta;
    }

    private static TransferObservation observe(
            int pendingCount,
            int previousDelta,
            int previousObservations,
            int playerDelta,
            int containerDelta,
            boolean allowZero
    ) {
        if (pendingCount <= 0) {
            throw new IllegalArgumentException("pending count must be positive");
        }
        boolean amountsMatch = playerDelta == containerDelta;
        boolean inRange = allowZero ? playerDelta >= 0 : playerDelta > 0;
        if (!amountsMatch || !inRange || playerDelta > pendingCount) {
            return new TransferObservation(-1, 0, false);
        }
        int observations = playerDelta == previousDelta
                ? previousObservations + 1
                : 1;
        return new TransferObservation(
                playerDelta,
                observations,
                observations >= STABLE_OBSERVATIONS
        );
    }

    record TransferObservation(
            int delta,
            int observations,
            boolean stable
    ) {
    }

    record Destination(int slot, int capacity) {
        Destination {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
            if (capacity < 0) {
                throw new IllegalArgumentException("capacity must not be negative");
            }
        }
    }

    record Placement(int slot, int count) {
        Placement {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("placement count must be positive");
            }
        }
    }
}
