package dev.stowling.automation;

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
}
