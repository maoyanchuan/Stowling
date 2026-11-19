package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerOpenHandPlanTest {
    @Test
    void neverUsesSelectedBuildingMaterialWhenAnotherSlotIsSafe() {
        assertEquals(1, ContainerOpenHandPlan.select(0, List.of(false, true, false)));
        assertEquals(2, ContainerOpenHandPlan.select(2, List.of(true, false, true)));
    }

    @Test
    void fullHotbarCanBorrowSafeSlotFromMainInventory() {
        List<Boolean> safe = new ArrayList<>(Collections.nCopies(36, false));
        safe.set(20, true);
        assertEquals(20, ContainerOpenHandPlan.select(0, safe));
        assertEquals(-1, ContainerOpenHandPlan.select(0, Collections.nCopies(36, false)));
    }
}
