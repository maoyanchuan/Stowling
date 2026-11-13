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

    private static int movedAmount(Pending pending) {
        Snapshot before = pending.before();
        Snapshot predicted = pending.predicted();
        if (before.menuId() != predicted.menuId()) {
            return 0;
        }
        return switch (pending.type()) {
            case EXTRACT -> extractAmount(pending.itemId(), before, predicted);
            case PICK_UP -> pickupAmount(pending.itemId(), before, predicted);
            case PLACE -> placeAmount(pending.itemId(), before, predicted);
        };
    }

    private static int extractAmount(
            String itemId,
            Snapshot before,
            Snapshot predicted
    ) {
        int removed = count(before.slot(), itemId) - count(predicted.slot(), itemId);
        int received = predicted.playerItemCount() - before.playerItemCount();
        return removed > 0 && removed == received ? removed : 0;
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

    private static int count(StackState stack, String itemId) {
        return stack.itemId().equals(itemId) ? stack.count() : 0;
    }

    enum ClickType {
        EXTRACT,
        PICK_UP,
        PLACE
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
            int playerItemCount
    ) {
        Snapshot {
            slot = Objects.requireNonNull(slot, "slot");
            carried = Objects.requireNonNull(carried, "carried");
            if (playerItemCount < 0) {
                throw new IllegalArgumentException("playerItemCount must not be negative");
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
