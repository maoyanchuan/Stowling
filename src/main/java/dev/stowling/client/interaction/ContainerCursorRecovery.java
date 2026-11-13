package dev.stowling.client.interaction;

import java.util.List;
import java.util.Objects;

final class ContainerCursorRecovery {
    private ContainerCursorRecovery() {
    }

    static NextStep nextStep(
            boolean hasRemaining,
            ContainerClickConfirmation.StackState carried
    ) {
        Objects.requireNonNull(carried, "carried");
        if (hasRemaining) {
            return NextStep.TRANSFER;
        }
        return carried.count() == 0 ? NextStep.ACKNOWLEDGE : NextStep.RETURN_CURSOR;
    }

    static int selectReturnSlot(
            List<SlotState> slots,
            int originSlot,
            String itemId
    ) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(itemId, "itemId");
        for (SlotState slot : slots) {
            if (slot.index() == originSlot && slot.canAccept(itemId)) {
                return slot.index();
            }
        }
        for (SlotState slot : slots) {
            if (slot.itemId().equals(itemId) && slot.canAccept(itemId)) {
                return slot.index();
            }
        }
        for (SlotState slot : slots) {
            if (slot.count() == 0 && slot.canAccept(itemId)) {
                return slot.index();
            }
        }
        return -1;
    }

    static boolean canQuickMoveWholeStack(int stackCount, int wanted) {
        return stackCount > 0 && stackCount <= wanted;
    }

    enum NextStep {
        TRANSFER,
        RETURN_CURSOR,
        ACKNOWLEDGE
    }

    record SlotState(
            int index,
            String itemId,
            int count,
            int maxCount,
            boolean acceptsItem
    ) {
        SlotState {
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (index < 0 || count < 0 || maxCount < 0 || count > maxCount) {
                throw new IllegalArgumentException("invalid player slot state");
            }
        }

        private boolean canAccept(String expectedItemId) {
            return acceptsItem
                    && count < maxCount
                    && (count == 0 || itemId.equals(expectedItemId));
        }
    }
}
