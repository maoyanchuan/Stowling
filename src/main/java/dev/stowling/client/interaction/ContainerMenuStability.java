package dev.stowling.client.interaction;

import java.util.List;
import java.util.Objects;

final class ContainerMenuStability {
    private Snapshot previous;

    boolean observe(Snapshot current) {
        Objects.requireNonNull(current, "current");
        boolean stable = current.equals(previous);
        previous = current;
        return stable;
    }

    record Snapshot(
            int menuId,
            List<ContainerClickConfirmation.StackState> slots,
            ContainerClickConfirmation.StackState carried
    ) {
        Snapshot {
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
            carried = Objects.requireNonNull(carried, "carried");
        }
    }
}
