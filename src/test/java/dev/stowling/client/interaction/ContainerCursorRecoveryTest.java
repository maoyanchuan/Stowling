package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerCursorRecoveryTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final ContainerClickConfirmation.StackState EMPTY = stack("", 0);

    @Test
    void returnsSixtyThreeItemsBeforeAcknowledgingSingleItemTransfer() {
        ContainerClickConfirmation.Pending pickup = pending(
                ContainerClickConfirmation.ClickType.PICK_UP,
                snapshot(stack(STONE, 64), EMPTY, 64, 0),
                snapshot(EMPTY, stack(STONE, 64), 0, 0)
        );
        ContainerClickConfirmation.Evaluation picked = confirm(pickup);
        assertEquals(64, picked.moved());

        ContainerClickConfirmation.Pending placeOne = pending(
                ContainerClickConfirmation.ClickType.PLACE,
                snapshot(EMPTY, stack(STONE, 64), 0, 0),
                snapshot(stack(STONE, 1), stack(STONE, 63), 0, 1)
        );
        ContainerClickConfirmation.Evaluation placed = confirm(placeOne);
        assertEquals(1, placed.moved());
        assertEquals(
                ContainerCursorRecovery.NextStep.RETURN_CURSOR,
                ContainerCursorRecovery.nextStep(false, placeOne.predicted().carried())
        );

        int originSlot = ContainerCursorRecovery.selectReturnSlot(
                List.of(slot(12, "", 0, 64, true)),
                12,
                STONE
        );
        assertEquals(12, originSlot);
        ContainerClickConfirmation.Pending returnCursor = pending(
                ContainerClickConfirmation.ClickType.RETURN_CURSOR,
                snapshot(EMPTY, stack(STONE, 63), 0, 1),
                snapshot(stack(STONE, 63), EMPTY, 63, 1)
        );
        ContainerClickConfirmation.Evaluation returned = confirm(returnCursor);

        assertEquals(63, returned.moved());
        assertEquals(
                ContainerCursorRecovery.NextStep.ACKNOWLEDGE,
                ContainerCursorRecovery.nextStep(false, returnCursor.predicted().carried())
        );
    }

    @Test
    void usesCompatibleStackBeforeEmptySlotWhenOriginIsUnavailable() {
        ContainerCursorRecovery.SlotState origin = slot(10, DIRT, 64, 64, true);
        ContainerCursorRecovery.SlotState compatible = slot(11, STONE, 32, 64, true);
        ContainerCursorRecovery.SlotState empty = slot(12, "", 0, 64, true);
        int selected = ContainerCursorRecovery.selectReturnSlot(
                List.of(origin, compatible, empty),
                10,
                STONE
        );

        assertEquals(11, selected);
    }

    @Test
    void reportsFailureWhenNoPlayerSlotCanAcceptCursor() {
        ContainerCursorRecovery.SlotState origin = slot(10, DIRT, 64, 64, true);
        ContainerCursorRecovery.SlotState full = slot(11, STONE, 64, 64, true);
        ContainerCursorRecovery.SlotState blocked = slot(12, "", 0, 64, false);
        int selected = ContainerCursorRecovery.selectReturnSlot(
                List.of(origin, full, blocked),
                10,
                STONE
        );

        assertEquals(-1, selected);
    }

    @Test
    void quickMoveNeverTransfersMoreThanRequested() {
        assertTrue(ContainerCursorRecovery.canQuickMoveWholeStack(1, 1));
        assertTrue(ContainerCursorRecovery.canQuickMoveWholeStack(32, 64));
        assertFalse(ContainerCursorRecovery.canQuickMoveWholeStack(64, 1));
    }

    private static ContainerClickConfirmation.Evaluation confirm(
            ContainerClickConfirmation.Pending pending
    ) {
        ContainerClickConfirmation.Evaluation result =
                ContainerClickConfirmation.evaluate(pending, pending.predicted());
        assertTrue(result.confirmed());
        return result;
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

    private static ContainerCursorRecovery.SlotState slot(
            int index,
            String itemId,
            int count,
            int maxCount,
            boolean accepts
    ) {
        return new ContainerCursorRecovery.SlotState(
                index,
                itemId,
                count,
                maxCount,
                accepts
        );
    }
}
