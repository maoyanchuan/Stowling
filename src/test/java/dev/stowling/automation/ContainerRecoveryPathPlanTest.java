package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerRecoveryPathPlanTest {
    private static final BlockPosition SITE = new BlockPosition(9, -44, 34);

    @Test
    void fallingDropNeverTurnsIntoAVoidDestination() {
        for (int y = -65; y >= -129; y--) {
            BlockPosition drop = new BlockPosition(9, y, 30);
            assertFalse(ContainerRecoveryPathPlan.withinArea(SITE, drop, -64, 320));
            assertTrue(ContainerRecoveryPathPlan.select(SITE, drop, SITE,
                    List.of(drop), -64, 320).isEmpty());
        }
        assertFalse(ContainerRecoveryPathPlan.withinArea(SITE,
                SITE.offset(0, -9, 0), -64, 320));
    }

    @Test
    void prefersSupportedFeetInsteadOfTheMovingEntityPosition() {
        BlockPosition drop = SITE.offset(0, 1, 0);
        BlockPosition neighbor = SITE.offset(1, 0, 0);
        assertEquals(SITE, ContainerRecoveryPathPlan.select(SITE, drop, neighbor,
                List.of(neighbor, SITE), -64, 320).orElseThrow());
        assertEquals(neighbor, ContainerRecoveryPathPlan.select(SITE, drop, neighbor,
                List.of(neighbor), -64, 320).orElseThrow());
        assertTrue(ContainerRecoveryPathPlan.select(SITE, drop, SITE,
                List.of(), -64, 320).isEmpty());
    }

    @Test
    void searchStaysAnchoredToThePlacementSiteAndKeepsPickupReach() {
        BlockPosition drop = SITE.offset(1, -1, 0);
        assertEquals(drop, ContainerRecoveryPathPlan.select(SITE, drop, SITE,
                List.of(SITE, drop, SITE.offset(3, -1, 0)), -64, 320).orElseThrow());
        assertTrue(ContainerRecoveryPathPlan.select(SITE, SITE.offset(5, 0, 0), SITE,
                List.of(SITE.offset(5, 0, 0)), -64, 320).isEmpty());
    }
}
