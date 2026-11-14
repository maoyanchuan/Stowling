package dev.stowling.client.interaction;

import java.util.Objects;

final class ContainerClickConfirmation {
    private ContainerClickConfirmation() {
    }

    static Evaluation evaluate(Pending pending, Snapshot observed) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(observed, "observed");
        int moved = movedAmount(pending);
        if (moved <= 0 || !pending.predicted().equals(observed)) {
            return new Evaluation(false, 0);
        }
        return new Evaluation(true, moved);
    }

    static boolean isUnchanged(Pending pending, Snapshot observed) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(observed, "observed");
        return pending.before().equals(observed);
    }

    private static int movedAmount(Pending pending) {
        Snapshot before = pending.before();
        Snapshot predicted = pending.predicted();
        if (before.menuId() != predicted.menuId()) {
            return 0;
        }
        return switch (pending.type()) {
            case EXTRACT -> extractAmount(pending.itemId(), before, predicted);
            case QUICK_MOVE -> quickMoveAmount(pending.itemId(), before, predicted);
            case PICK_UP -> pickupAmount(pending.itemId(), before, predicted);
            case PLACE -> placeAmount(pending.itemId(), before, predicted);
            case RETURN_CURSOR -> returnAmount(pending.itemId(), before, predicted);
        };
    }

    private static int extractAmount(
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        int removed = count(before.slot(), itemId) - count(predicted.slot(), itemId);
        int received = predicted.playerItemCount() - before.playerItemCount();
        int containerRemoved = before.containerItemCount()
                - predicted.containerItemCount();
        return removed > 0 && removed == received && removed == containerRemoved
                ? removed
                : 0;
    }

    private static int quickMoveAmount(
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        int slotRemoved = count(before.slot(), itemId) - count(predicted.slot(), itemId);
        int playerRemoved = before.playerItemCount() - predicted.playerItemCount();
        int containerAdded = predicted.containerItemCount()
                - before.containerItemCount();
        boolean cursorStayedEmpty = before.carried().count() == 0
                && predicted.carried().count() == 0;
        return slotRemoved > 0
                && slotRemoved == playerRemoved
                && slotRemoved == containerAdded
                && cursorStayedEmpty
                ? slotRemoved
                : 0;
    }

    private static int pickupAmount(
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        int removed = count(before.slot(), itemId) - count(predicted.slot(), itemId);
        int carried = count(predicted.carried(), itemId) - count(before.carried(), itemId);
        int inventoryRemoved = before.playerItemCount() - predicted.playerItemCount();
        return removed > 0 && removed == carried && removed == inventoryRemoved ? removed : 0;
    }

    private static int placeAmount(
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        int carried = count(before.carried(), itemId) - count(predicted.carried(), itemId);
        int placed = count(predicted.slot(), itemId) - count(before.slot(), itemId);
        return carried > 0
                && carried == placed
                && before.playerItemCount() == predicted.playerItemCount()
                ? carried
                : 0;
    }

    private static int returnAmount(
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        int carried = count(before.carried(), itemId) - count(predicted.carried(), itemId);
        int returned = count(predicted.slot(), itemId) - count(before.slot(), itemId);
        int inventoryAdded = predicted.playerItemCount() - before.playerItemCount();
        return carried > 0 && carried == returned && carried == inventoryAdded ? carried : 0;
    }

    private static int count(StackState stack, String itemId) {
        return stack.itemId().equals(itemId) ? stack.count() : 0;
    }

    enum ClickType {
        EXTRACT,
        QUICK_MOVE,
        PICK_UP,
        PLACE,
        RETURN_CURSOR
    }

    record StackState(String itemId, int count) {
        StackState {
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }
    }

    record Snapshot(
            int menuId,
            StackState slot,
            StackState carried,
            int playerItemCount,
            int containerItemCount
    ) {
        Snapshot {
            slot = Objects.requireNonNull(slot, "slot");
            carried = Objects.requireNonNull(carried, "carried");
            if (playerItemCount < 0 || containerItemCount < 0) {
                throw new IllegalArgumentException("item counts must not be negative");
            }
        }
    }

    record Pending(
            ClickType type,
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        Pending {
            type = Objects.requireNonNull(type, "type");
            itemId = Objects.requireNonNull(itemId, "itemId");
            before = Objects.requireNonNull(before, "before");
            predicted = Objects.requireNonNull(predicted, "predicted");
        }
    }

    record Evaluation(boolean confirmed, int moved) {
        Evaluation {
            if (moved < 0) {
                throw new IllegalArgumentException("moved must not be negative");
            }
        }
    }
}
