package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerMenuStabilityTest {
    private static final ContainerClickConfirmation.StackState EMPTY =
            new ContainerClickConfirmation.StackState("", 0);

    @Test
    void requiresAnUnchangedPollAfterMenuContentsArrive() {
        ContainerMenuStability stability = new ContainerMenuStability();
        ContainerMenuStability.Snapshot empty = snapshot(EMPTY);
        ContainerMenuStability.Snapshot initialized = snapshot(
                new ContainerClickConfirmation.StackState("minecraft:stone", 64)
        );

        assertFalse(stability.observe(empty));
        assertFalse(stability.observe(initialized));
        assertTrue(stability.observe(initialized));
    }

    private static ContainerMenuStability.Snapshot snapshot(
            ContainerClickConfirmation.StackState slot
    ) {
        return new ContainerMenuStability.Snapshot(7, List.of(slot), EMPTY);
    }
}
