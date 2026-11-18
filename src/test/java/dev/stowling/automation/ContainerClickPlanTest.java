package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContainerClickPlanTest {
    @Test
    void exactMovesPreserveSourceRemaindersAndExistingDestinationItems() {
        for (int size : new int[] {1, 16, 64}) {
            for (int source = 1; source <= size; source++) {
                for (int amount = 1; amount <= source; amount++) {
                    simulate(size, source, 0, amount);
                    simulate(size, source, size - amount, amount);
                }
            }
        }
    }

    @Test
    void halfAndNearFullTransfersAvoidClickingEveryItem() {
        assertEquals(2, ContainerClickPlan.exact(64, 64).size());
        assertEquals(2, ContainerClickPlan.exact(64, 32).size());
        assertEquals(3, ContainerClickPlan.exact(64, 63).size());
        assertEquals(5, ContainerClickPlan.exact(64, 47).size());
        assertEquals(3, ContainerClickPlan.exact(64, 1).size());
        assertThrows(IllegalArgumentException.class, () -> ContainerClickPlan.exact(64, 65));
        assertThrows(IllegalArgumentException.class, () -> ContainerClickPlan.exact(64, 0));
    }

    private static void simulate(int limit, int initialSource, int initialDestination, int amount) {
        int[] slots = {initialSource, initialDestination};
        int carried = 0;
        for (var click : ContainerClickPlan.exact(initialSource, amount)) {
            int index = click.source() ? 0 : 1;
            if (carried == 0) {
                int picked = click.button() == 0 ? slots[index] : (slots[index] + 1) / 2;
                carried = picked;
                slots[index] -= picked;
            } else {
                int placed = Math.min(limit - slots[index], click.button() == 0 ? carried : 1);
                slots[index] += placed;
                carried -= placed;
            }
            assertTrue(slots[0] >= 0 && slots[0] <= limit);
            assertTrue(slots[1] >= 0 && slots[1] <= limit);
            assertEquals(initialSource + initialDestination, slots[0] + slots[1] + carried);
        }
        assertEquals(initialSource - amount, slots[0]);
        assertEquals(initialDestination + amount, slots[1]);
        assertEquals(0, carried);
    }
}
