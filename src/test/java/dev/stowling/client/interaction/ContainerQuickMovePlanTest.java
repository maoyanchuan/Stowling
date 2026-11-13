package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContainerQuickMovePlanTest {
    private static final String STONE = "minecraft:stone";

    @Test
    void selectsThreeFullStacksOneAtATimeWithoutUsingCursor() {
        List<ContainerQuickMovePlan.Candidate> candidates = List.of(
                candidate(36, 64, 1728),
                candidate(37, 64, 1664),
                candidate(38, 64, 1600)
        );

        assertEquals(36, select(candidates, 192));
        assertEquals(37, select(candidates.subList(1, 3), 128));
        assertEquals(38, select(candidates.subList(2, 3), 64));
    }

    @Test
    void skipsWholeStackWhenDestinationCapacityIsInsufficient() {
        assertEquals(-1, select(List.of(candidate(36, 64, 63)), 64));
    }

    @Test
    void skipsWholeStackWhenOnlyOneItemRemains() {
        assertEquals(-1, select(List.of(candidate(36, 64, 1728)), 1));
    }

    private static int select(
            List<ContainerQuickMovePlan.Candidate> candidates,
            int remaining
    ) {
        return ContainerQuickMovePlan.selectPlayerSlot(
                candidates,
                Map.of(STONE, remaining)
        );
    }

    private static ContainerQuickMovePlan.Candidate candidate(
            int slot,
            int count,
            int capacity
    ) {
        return new ContainerQuickMovePlan.Candidate(slot, STONE, count, capacity);
    }
}
