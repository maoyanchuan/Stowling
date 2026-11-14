package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PickupWorkflowPortTest {
    @Test
    void legacyRequestStartsImmediatelyAndNormalizesReferences() {
        PickupWorkflowPort.Request request = new PickupWorkflowPort.Request(
                " farm ", Optional.of(" north ")
        );

        assertEquals("farm", request.workflowId());
        assertEquals(Optional.of("north"), request.regionId());
        assertFalse(request.waitForItems());
    }

    @Test
    void waitingRequestCanObserveTheWholeWorkflow() {
        PickupWorkflowPort.Request request = new PickupWorkflowPort.Request(
                "farm", Optional.empty(), true
        );

        assertTrue(request.regionId().isEmpty());
        assertTrue(request.waitForItems());
    }
}
