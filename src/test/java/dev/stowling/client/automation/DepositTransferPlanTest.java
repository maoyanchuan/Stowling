package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DepositTransferPlanTest {
    @Test
    void requestingTwoFromTenKeepsEightInSource() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                2,
                10,
                List.of(new DepositTransferPlan.Destination(25, 64))
        );

        assertFalse(plan.usesQuickMove());
        assertEquals(2, plan.moveCount());
        assertEquals(8, plan.sourceRemainderAfterMove());
        assertEquals(0, plan.remainingAfterConfirmed());
    }

    @Test
    void capacityOfOneMovesOneAndLeavesOneRequested() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                2,
                10,
                List.of(new DepositTransferPlan.Destination(8, 1))
        );

        assertEquals(1, plan.moveCount());
        assertEquals(9, plan.sourceRemainderAfterMove());
        assertEquals(1, plan.remainingAfterConfirmed());
    }

    @Test
    void mixedFoodStackUsesExactSplit() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                2,
                7,
                List.of(new DepositTransferPlan.Destination(12, 64))
        );

        assertFalse(plan.usesQuickMove());
        assertEquals(2, plan.moveCount());
        assertEquals(5, plan.sourceRemainderAfterMove());
    }

    @Test
    void completeStackKeepsQuickMovePath() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                4,
                4,
                List.of(new DepositTransferPlan.Destination(3, 4))
        );

        assertTrue(plan.usesQuickMove());
        assertEquals(4, plan.moveCount());
        assertEquals(0, plan.sourceRemainderAfterMove());
    }

    @Test
    void noCapacityLeavesRequestedAmount() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                2,
                10,
                List.of(new DepositTransferPlan.Destination(0, 0))
        );

        assertFalse(plan.hasMovement());
        assertEquals(0, plan.moveCount());
        assertEquals(10, plan.sourceRemainderAfterMove());
        assertEquals(2, plan.remainingAfterConfirmed());
    }

    @Test
    void unconfirmedAndFailedTransfersKeepAllRequestedRemaining() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                2,
                10,
                List.of(new DepositTransferPlan.Destination(6, 1))
        );

        assertEquals(1, plan.remainingAfterConfirmed());
        assertEquals(2, plan.remainingAfterUnconfirmed());
        assertEquals(2, plan.remainingAfterFailure());
    }

    @Test
    void splitsAcrossRealDestinationSlots() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                3,
                10,
                List.of(
                        new DepositTransferPlan.Destination(4, 1),
                        new DepositTransferPlan.Destination(9, 2),
                        new DepositTransferPlan.Destination(10, 4)
                )
        );

        assertEquals(
                List.of(
                        new DepositTransferPlan.Placement(4, 1),
                        new DepositTransferPlan.Placement(9, 2)
                ),
                plan.placements()
        );
    }

    @Test
    void partialServerAcceptanceUsesStableActualDelta() {
        DepositTransferPlan.TransferObservation first =
                DepositTransferPlan.observeConfirmation(2, -1, 0, 1, 1);
        DepositTransferPlan.TransferObservation second =
                DepositTransferPlan.observeConfirmation(
                        2,
                        first.delta(),
                        first.observations(),
                        1,
                        1
                );

        assertFalse(first.stable());
        assertTrue(second.stable());
        assertEquals(1, second.delta());
    }

    @Test
    void fullServerAcceptanceUsesStablePendingCount() {
        DepositTransferPlan.TransferObservation first =
                DepositTransferPlan.observeConfirmation(2, -1, 0, 2, 2);
        DepositTransferPlan.TransferObservation second =
                DepositTransferPlan.observeConfirmation(
                        2,
                        first.delta(),
                        first.observations(),
                        2,
                        2
                );

        assertTrue(second.stable());
        assertEquals(2, second.delta());
    }

    @Test
    void mismatchedOrExcessiveCountsAreNotStable() {
        DepositTransferPlan.TransferObservation mismatched =
                DepositTransferPlan.observeConfirmation(2, 1, 1, 1, 0);
        DepositTransferPlan.TransferObservation excessive =
                DepositTransferPlan.observeConfirmation(2, 2, 1, 3, 3);

        assertFalse(mismatched.stable());
        assertFalse(excessive.stable());
    }

    @Test
    void zeroDeltaRequiresStableSettlementObservationBeforeFailure() {
        DepositTransferPlan.TransferObservation first =
                DepositTransferPlan.observeSettlement(2, -1, 0, 0, 0);
        DepositTransferPlan.TransferObservation second =
                DepositTransferPlan.observeSettlement(
                        2,
                        first.delta(),
                        first.observations(),
                        0,
                        0
                );

        assertFalse(first.stable());
        assertTrue(second.stable());
        assertEquals(0, second.delta());
    }

    @Test
    void changedDeltaRestartsStableObservationCount() {
        DepositTransferPlan.TransferObservation changed =
                DepositTransferPlan.observeConfirmation(2, 1, 1, 2, 2);

        assertEquals(1, changed.observations());
        assertFalse(changed.stable());
    }

    @Test
    void acceptedPartialDeltaLeavesOnlyUnacceptedRequestedAmount() {
        DepositTransferPlan plan = DepositTransferPlan.create(
                2,
                10,
                List.of(new DepositTransferPlan.Destination(6, 2))
        );

        assertEquals(1, plan.remainingAfterAccepted(1));
        assertEquals(0, plan.remainingAfterAccepted(2));
    }
}
