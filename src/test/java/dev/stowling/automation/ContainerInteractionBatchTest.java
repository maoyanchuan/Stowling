package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContainerInteractionBatchTest {
    @Test
    void distinctBoxKeysCanUseOneWaveWithoutBypassingTheClickBudget() {
        var batch = new ContainerInteractionBatch("first", true);
        for (int box = 0; box < 27; box++) {
            assertTrue(batch.accepts("box-" + box, 2));
            batch.record(1, 2);
        }
        assertEquals(27, batch.count());
        assertTrue(batch.accepts("other", 10));
        assertFalse(batch.accepts("other", 11));
    }
    @Test
    void aWaveCombinesThePlayerInventoryButKeepsExactItemComponentsSeparate() {
        var batch = new ContainerInteractionBatch("minecraft:stone#one");
        assertFalse(batch.accepts("minecraft:stone#two", 1));
        for (int i = 0; i < 36; i++) {
            assertTrue(batch.accepts("minecraft:stone#one", 1));
            batch.record(64, 1);
        }
        assertEquals(2304, batch.count());
        assertFalse(batch.accepts("minecraft:stone#one", 1));
        assertFalse(batch.accepts("minecraft:stone#two", 1));
        assertThrows(IllegalArgumentException.class, () -> batch.record(64, 1));
    }

    @Test
    void exactTransfersRespectTheClickBudgetAndRejectZeroMovement() {
        var batch = new ContainerInteractionBatch("minecraft:stone");
        batch.record(32, 60);
        assertTrue(batch.accepts("minecraft:stone", 4));
        assertFalse(batch.accepts("minecraft:stone", 5));
        assertThrows(IllegalArgumentException.class, () -> batch.record(0, 1));
        batch.record(1, 4);
        assertEquals(33, batch.count());
        assertFalse(batch.accepts("minecraft:stone", 1));
    }

    @Test
    void aNewWindowCannotReuseAnotherWindowsInitialization() {
        var tracker = new ContainerSyncTracker();
        Object oldMenu = new Object();
        Object newMenu = new Object();
        assertFalse(tracker.ready(oldMenu));
        tracker.initialized(oldMenu);
        assertTrue(tracker.ready(oldMenu));
        assertFalse(tracker.ready(newMenu));
        tracker.initialized(newMenu);
        assertTrue(tracker.ready(newMenu));
    }
}
