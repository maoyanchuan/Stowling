package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContainerClickConfirmationTest {
    private static final String STONE = "minecraft:stone";
    private static final ContainerClickConfirmation.StackState EMPTY = stack("", 0);

    @Test
    void confirmsExtractFromStablePredictedState() {
        ContainerClickConfirmation.Pending pending = pending(
                ContainerClickConfirmation.ClickType.EXTRACT,
                snapshot(stack(STONE, 32), EMPTY, 0, 32),
                snapshot(EMPTY, EMPTY, 32, 0)
        );

        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, pending.predicted());

        assertTrue(result.confirmed());
        assertEquals(32, result.moved());
    }

    @Test
    void confirmsPickupFromStablePredictedState() {
        ContainerClickConfirmation.Pending pending = pending(
                ContainerClickConfirmation.ClickType.PICK_UP,
                snapshot(stack(STONE, 16), EMPTY, 16, 0),
                snapshot(EMPTY, stack(STONE, 16), 0, 0)
        );

        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, pending.predicted());

        assertTrue(result.confirmed());
        assertEquals(16, result.moved());
    }

    @Test
    void confirmsPlaceFromStablePredictedState() {
        ContainerClickConfirmation.Pending pending = pending(
                ContainerClickConfirmation.ClickType.PLACE,
                snapshot(EMPTY, stack(STONE, 12), 0, 0),
                snapshot(stack(STONE, 12), EMPTY, 0, 12)
        );

        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, pending.predicted());

        assertTrue(result.confirmed());
        assertEquals(12, result.moved());
    }

    @Test
    void rejectsServerCorrectionThatDiffersFromPrediction() {
        ContainerClickConfirmation.Snapshot before = snapshot(
                stack(STONE, 8),
                EMPTY,
                0,
                8
        );
        ContainerClickConfirmation.Pending pending = pending(
                ContainerClickConfirmation.ClickType.EXTRACT,
                before,
                snapshot(EMPTY, EMPTY, 8, 0)
        );

        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, before);

        assertFalse(result.confirmed());
        assertEquals(0, result.moved());
    }

    @Test
    void rejectsPredictionWithoutAnActualMove() {
        ContainerClickConfirmation.Snapshot unchanged = snapshot(
                stack(STONE, 8),
                EMPTY,
                0,
                8
        );
        ContainerClickConfirmation.Pending pending = pending(
                ContainerClickConfirmation.ClickType.EXTRACT,
                unchanged,
                unchanged
        );

        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, unchanged);

        assertFalse(result.confirmed());
        assertEquals(0, result.moved());
    }

    @Test
    void confirmsThreeQuickMovesWithMatchingCountsAndAnEmptyCursor() {
        for (int movedStacks = 0; movedStacks < 3; movedStacks++) {
            int playerBefore = 192 - movedStacks * 64;
            int containerBefore = movedStacks * 64;
            ContainerClickConfirmation.Pending pending = pending(
                    ContainerClickConfirmation.ClickType.QUICK_MOVE,
                    snapshot(stack(STONE, 64), EMPTY, playerBefore, containerBefore),
                    snapshot(EMPTY, EMPTY, playerBefore - 64, containerBefore + 64)
            );

            ContainerClickConfirmation.Evaluation result =
                    ContainerClickConfirmation.evaluate(pending, pending.predicted());

            assertTrue(result.confirmed());
            assertEquals(64, result.moved());
            assertEquals(EMPTY, pending.predicted().carried());
        }
    }

    @Test
    void rejectsQuickMoveWhenContainerIncreaseDoesNotMatchPlayerDecrease() {
        ContainerClickConfirmation.Pending pending = pending(
                ContainerClickConfirmation.ClickType.QUICK_MOVE,
                snapshot(stack(STONE, 64), EMPTY, 192, 0),
                snapshot(EMPTY, EMPTY, 128, 32)
        );

        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, pending.predicted());

        assertFalse(result.confirmed());
        assertEquals(0, result.moved());
    }

    private static ContainerClickConfirmation.Pending pending(
            ContainerClickConfirmation.ClickType type,
            ContainerClickConfirmation.Snapshot before,
            ContainerClickConfirmation.Snapshot predicted
    ) {
        return new ContainerClickConfirmation.Pending(type, STONE, before, predicted);
    }

    private static ContainerClickConfirmation.Snapshot snapshot(
            ContainerClickConfirmation.StackState slot,
            ContainerClickConfirmation.StackState carried,
            int playerCount,
            int containerCount
    ) {
        return new ContainerClickConfirmation.Snapshot(
                7,
                slot,
                carried,
                playerCount,
                containerCount
        );
    }

    private static ContainerClickConfirmation.StackState stack(String itemId, int count) {
        return new ContainerClickConfirmation.StackState(itemId, count);
    }
}
