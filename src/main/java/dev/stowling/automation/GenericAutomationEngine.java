package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes typed automation modules in workflow order.
 *
 * <p>The checkpoint contains only confirmed actions. A module resumed after
 * restart receives its durable module state and must reconcile authoritative
 * game state before issuing another container action.
 */
public final class GenericAutomationEngine {
    private final UUID taskId;
    private final AutomationWorkflow workflow;
    private final AutomationModuleRegistry registry;
    private final GenericAutomationCheckpointStore checkpoints;

    private AutomationExecutionStatus status = AutomationExecutionStatus.READY;
    private int stepIndex;
    private Map<String, String> moduleState = Map.of();
    private Optional<String> lastConfirmedAction = Optional.empty();
    private Optional<String> message = Optional.empty();
    private boolean started;
    private boolean recovered;

    public GenericAutomationEngine(
            UUID taskId,
            AutomationWorkflow workflow,
            AutomationModuleRegistry registry,
            GenericAutomationCheckpointStore checkpoints
    ) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Optional<GenericAutomationCheckpoint> stored = checkpoints.load(taskId);
        if (stored.isPresent()) {
            restore(stored.orElseThrow());
            return;
        }
        status = AutomationExecutionStatus.RUNNING;
        recovered = false;
        saveCheckpoint();
    }

    public synchronized GenericAutomationSnapshot tick() {
        start();
        if (status == AutomationExecutionStatus.READY) {
            status = AutomationExecutionStatus.RUNNING;
        }
        if (status != AutomationExecutionStatus.RUNNING) {
            return snapshot();
        }
        if (stepIndex >= workflow.steps().size()) {
            status = AutomationExecutionStatus.SUCCEEDED;
            saveCheckpoint();
            return snapshot();
        }
        AutomationStep step = workflow.steps().get(stepIndex);
        AutomationModule module = registry.require(step.type());
        AutomationModuleContext context = new AutomationModuleContext(
                taskId,
                step,
                moduleState,
                recovered,
                lastConfirmedAction
        );
        AutomationModuleResult result = Objects.requireNonNull(
                module.execute(context),
                "module result"
        );
        recovered = false;
        apply(result);
        return snapshot();
    }

    public synchronized void pause(String reason) {
        start();
        if (status != AutomationExecutionStatus.RUNNING) {
            return;
        }
        status = AutomationExecutionStatus.PAUSED;
        message = Optional.of(Objects.requireNonNull(reason, "reason"));
        saveCheckpoint();
    }

    public synchronized void resume() {
        start();
        if (status != AutomationExecutionStatus.PAUSED) {
            return;
        }
        status = AutomationExecutionStatus.RUNNING;
        message = Optional.empty();
        saveCheckpoint();
    }

    public synchronized void cancel() {
        start();
        if (status == AutomationExecutionStatus.SUCCEEDED
                || status == AutomationExecutionStatus.FAILED) {
            return;
        }
        status = AutomationExecutionStatus.FAILED;
        message = Optional.of("cancelled by control request");
    }

    public synchronized GenericAutomationSnapshot snapshot() {
        return new GenericAutomationSnapshot(
                taskId,
                workflow.id(),
                status,
                stepIndex,
                workflow.steps().size(),
                moduleState,
                lastConfirmedAction,
                message
        );
    }

    private void apply(AutomationModuleResult result) {
        moduleState = result.moduleState();
        if (result.lastConfirmedAction().isPresent()) {
            lastConfirmedAction = result.lastConfirmedAction();
        }
        switch (result.status()) {
            case RUNNING -> {
                status = AutomationExecutionStatus.RUNNING;
                message = result.message();
            }
            case SUCCEEDED -> advanceStep();
            case PAUSED -> {
                status = AutomationExecutionStatus.PAUSED;
                message = result.message();
            }
            case FAILED -> {
                status = AutomationExecutionStatus.FAILED;
                message = result.message();
            }
        }
        saveCheckpoint();
    }

    private void advanceStep() {
        stepIndex++;
        moduleState = Map.of();
        message = Optional.empty();
        if (stepIndex >= workflow.steps().size()) {
            status = AutomationExecutionStatus.SUCCEEDED;
        } else {
            status = AutomationExecutionStatus.RUNNING;
        }
    }

    private void restore(GenericAutomationCheckpoint checkpoint) {
        if (!checkpoint.workflowId().equals(workflow.id())) {
            throw new IllegalArgumentException("checkpoint workflow does not match");
        }
        if (checkpoint.stepIndex() > workflow.steps().size()) {
            throw new IllegalArgumentException("checkpoint step is outside workflow");
        }
        stepIndex = checkpoint.stepIndex();
        moduleState = checkpoint.moduleState();
        lastConfirmedAction = checkpoint.lastConfirmedAction();
        status = checkpoint.status();
        recovered = true;
        if (status == AutomationExecutionStatus.READY) {
            status = AutomationExecutionStatus.RUNNING;
        }
        message = Optional.empty();
    }

    private void saveCheckpoint() {
        checkpoints.save(new GenericAutomationCheckpoint(
                taskId,
                workflow.id(),
                stepIndex,
                moduleState,
                lastConfirmedAction,
                status
        ));
    }
}
