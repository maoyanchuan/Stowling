package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContainerSyncTrackerTest {
    @Test
    void initializationDoesNotConfirmAFutureBatchAndWindowNumbersAreNotReused() {
        var tracker = new ContainerSyncTracker();
        Object menu = new Object();
        Object replacement = new Object();
        assertFalse(tracker.ready(menu));
        tracker.initialized(menu);
        long before = tracker.generation(menu);
        assertTrue(tracker.ready(menu));
        assertFalse(tracker.confirmed(menu, menu, before));
        tracker.initialized(menu);
        assertTrue(tracker.confirmed(menu, menu, before));
        tracker.initialized(replacement);
        assertFalse(tracker.confirmed(replacement, menu, before));
    }
}
