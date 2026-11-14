package dev.stowling.automation;

import dev.stowling.schedule.StopOutcome;
import java.util.UUID;

/**
 * A durable operation contract used by automations that can span ticks.
 *
 * <p>The operation key must identify the same logical request across restarts.
 * A caller that finds an existing key must reconcile or poll instead of
 * starting the request again.
 *
 * @param <Request> operation request type
 */
public interface AutomationOperationPort<Request> {
    AutomationOperationResult start(String operationKey, Request request);

    AutomationOperationResult poll(String operationKey);

    AutomationOperationResult reconcile(String operationKey);

    /**
     * Requests a safe stop for operations owned by the task.
     *
     * <p>Implementations without confirmed cancellation must return
     * {@link StopOutcome#UNSAFE} rather than claiming a clean stop.
     */
    default StopOutcome requestStop(UUID attemptId) {
        return StopOutcome.UNSAFE;
    }

    default StopOutcome pollStop(UUID attemptId) {
        return StopOutcome.UNSAFE;
    }
}
