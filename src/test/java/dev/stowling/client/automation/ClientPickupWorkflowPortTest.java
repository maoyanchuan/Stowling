package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationOperationStatus;
import dev.stowling.schedule.StopOutcome;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ClientPickupWorkflowPortTest {
    @Test
    void stopHelperRequestsBeforePollingAndPropagatesUnsafe() {
        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicBoolean stopPolled = new AtomicBoolean();

        StopOutcome outcome = ClientPickupWorkflowPort.requestStopAndPoll(
                () -> stopRequested.set(true),
                () -> {
                    assertTrue(stopRequested.get());
                    stopPolled.set(true);
                    return StopOutcome.UNSAFE;
                }
        );

        assertEquals(StopOutcome.UNSAFE, outcome);
        assertTrue(stopPolled.get());
    }

    @Test
    void completionSucceedsOnlyAfterCleanStop() {
        AutomationOperationResult result =
                ClientPickupWorkflowPort.stopCompletionResult(
                        "quarry",
                        StopOutcome.CLEAN
                );

        assertEquals(AutomationOperationStatus.SUCCEEDED, result.status());
        assertEquals(
                "RUN_PICKUP:quarry",
                result.lastConfirmedAction().orElseThrow()
        );
    }

    @Test
    void completionRemainsRunningWhileStopIsPending() {
        AutomationOperationResult result =
                ClientPickupWorkflowPort.stopCompletionResult(
                        "quarry",
                        StopOutcome.PENDING
                );

        assertEquals(AutomationOperationStatus.RUNNING, result.status());
        assertTrue(result.message().orElseThrow().contains("clean"));
    }

    @Test
    void completionFailsWhenStopIsUnsafe() {
        AutomationOperationResult result =
                ClientPickupWorkflowPort.stopCompletionResult(
                        "quarry",
                        StopOutcome.UNSAFE
                );

        assertEquals(AutomationOperationStatus.FAILED, result.status());
        assertTrue(result.lastConfirmedAction().isEmpty());
    }
}
