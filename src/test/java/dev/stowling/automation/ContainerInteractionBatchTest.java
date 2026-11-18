package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContainerInteractionBatchTest {
    @Test
    void aWaveCombinesEightStacksButKeepsExactItemComponentsSeparate() {
        var batch = new ContainerInteractionBatch("minecraft:stone#one");
        assertFalse(batch.accepts("minecraft:stone#two", 1));
        for (int i = 0; i < 8; i++) {
            assertTrue(batch.accepts("minecraft:stone#one", 1));
            batch.record(64, 1);
        }
        assertEquals(512, batch.count());
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
