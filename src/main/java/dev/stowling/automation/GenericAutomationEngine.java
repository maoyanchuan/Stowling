package dev.stowling.automation;

import dev.stowling.schedule.StopOutcome;
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
    private boolean stopRequested;
    private StopOutcome stopOutcome = StopOutcome.CLEAN;
    private AutomationModule activeModule;
    private AutomationModuleContext activeContext;
    private boolean moduleExecutionUnconfirmed;

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
        if (stopRequested && stopOutcome != StopOutcome.CLEAN) {
            return snapshot();
        }
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
        module.descriptor().requireValid(step.parameters());
        AutomationModuleContext context = new AutomationModuleContext(
                taskId,
                step,
                moduleState,
                recovered,
                lastConfirmedAction
        );
        rememberActiveModule(module, context, AutomationModuleStatus.RUNNING);
        AutomationModuleResult result;
        try {
            result = Objects.requireNonNull(
                    module.execute(context),
                    "module result"
            );
        } catch (RuntimeException exception) {
            moduleExecutionUnconfirmed = true;
            throw exception;
        }
        moduleExecutionUnconfirmed = false;
        recovered = false;
        rememberActiveModule(module, context, result.status());
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
        requestStop();
    }

    public synchronized void requestStop() {
        start();
        if (stopRequested) {
            return;
        }
        if (activeModule == null
                && activeContext == null
                && moduleState.isEmpty()) {
            stopRequested = true;
            stopOutcome = StopOutcome.CLEAN;
            return;
        }
        stopRequested = true;
        stopOutcome = StopOutcome.PENDING;
        message = Optional.of("stop requested");
        if (moduleExecutionUnconfirmed) {
            stopOutcome = StopOutcome.UNSAFE;
            finishStop(stopOutcome);
            return;
        }
        AutomationModuleContext context;
        try {
            context = stopContext();
        } catch (RuntimeException exception) {
            stopOutcome = StopOutcome.UNSAFE;
            finishStop(stopOutcome);
            return;
        }
        if (context == null) {
            finishStop(StopOutcome.CLEAN);
            return;
        }
        try {
            stopOutcome = Objects.requireNonNull(
                    activeModule.stop(context),
                    "module stop outcome"
            );
        } catch (RuntimeException exception) {
            stopOutcome = StopOutcome.UNSAFE;
        }
        if (stopOutcome != StopOutcome.PENDING) {
            finishStop(stopOutcome);
        }
    }

    public synchronized StopOutcome pollStop() {
        if (!stopRequested || stopOutcome != StopOutcome.PENDING) {
            return stopOutcome;
        }
        if (moduleExecutionUnconfirmed) {
            stopOutcome = StopOutcome.UNSAFE;
            finishStop(stopOutcome);
            return stopOutcome;
        }
        AutomationModuleContext context;
        try {
            context = stopContext();
        } catch (RuntimeException exception) {
            stopOutcome = StopOutcome.UNSAFE;
            finishStop(stopOutcome);
            return stopOutcome;
        }
        if (context == null) {
            finishStop(StopOutcome.CLEAN);
            return stopOutcome;
        }
        try {
            stopOutcome = Objects.requireNonNull(
                    activeModule.pollStop(context),
                    "module stop poll outcome"
            );
        } catch (RuntimeException exception) {
            stopOutcome = StopOutcome.UNSAFE;
        }
        if (stopOutcome != StopOutcome.PENDING) {
            finishStop(stopOutcome);
        }
        return stopOutcome;
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
        activeModule = null;
        activeContext = null;
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

    private void rememberActiveModule(
            AutomationModule module,
            AutomationModuleContext context,
            AutomationModuleStatus resultStatus
    ) {
        activeModule = switch (resultStatus) {
            case RUNNING, PAUSED, FAILED -> module;
            case SUCCEEDED -> null;
        };
        activeContext = activeModule == null ? null : context;
    }

    private AutomationModuleContext stopContext() {
        if (activeModule != null && activeContext != null) {
            return activeContext;
        }
        if (stepIndex >= workflow.steps().size()) {
            return null;
        }
        AutomationStep step = workflow.steps().get(stepIndex);
        activeModule = registry.require(step.type());
        activeContext = new AutomationModuleContext(
                taskId,
                step,
                moduleState,
                recovered,
                lastConfirmedAction
        );
        return activeContext;
    }

    private void finishStop(StopOutcome outcome) {
        if (outcome == StopOutcome.CLEAN
                && status != AutomationExecutionStatus.FAILED) {
            status = AutomationExecutionStatus.FAILED;
            message = Optional.of("cancelled by control request");
        } else if (outcome == StopOutcome.UNSAFE
                && status != AutomationExecutionStatus.FAILED) {
            status = AutomationExecutionStatus.FAILED;
            message = Optional.of("automation stop could not be confirmed");
        }
        activeModule = null;
        activeContext = null;
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
