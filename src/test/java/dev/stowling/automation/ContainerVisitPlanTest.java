package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerVisitPlanTest {
    @Test
    void selectsFromLatestPositionWithoutRevisitingDuplicateContainers() {
        ContainerTarget first = new ContainerTarget(new BlockPosition(1, 64, 0), "chest");
        ContainerTarget duplicate = new ContainerTarget(first.position(), "container");
        ContainerTarget west = new ContainerTarget(new BlockPosition(-2, 64, 0), "chest");
        ContainerTarget east = new ContainerTarget(new BlockPosition(10, 64, 0), "chest");
        ContainerVisitPlan plan = new ContainerVisitPlan(List.of(west, first, duplicate, east));

        assertEquals(first, plan.next(new BlockPosition(0, 64, 0)).orElseThrow());
        assertEquals(east, plan.next(new BlockPosition(9, 64, 0)).orElseThrow());
        assertEquals(west, plan.next(east.position()).orElseThrow());
        assertTrue(plan.next(west.position()).isEmpty());
    }
}
