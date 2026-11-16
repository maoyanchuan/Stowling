package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class ReadContainerModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private boolean started;
    private boolean inFlight;

    ReadContainerModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        ResourceReference reference = ResourceReferenceSupport.optionalReference(
                step,
                TaskModuleIds.TARGET_REF,
                ResourceType.OUTPUT
        );
        ContainerTarget selected = ResourceReferenceSupport.markerContainer(
                context.workspace()
        );
        ContainerTarget requested = reference == null
                ? selected
                : ResourceReferenceSupport.output(context.workspace(), reference);
        if (!selected.equals(requested)) {
            throw new IllegalArgumentException(
                    "READ_CONTAINER target must match the selected marker"
            );
        }
        this.target = selected;
    }

    @Override
    public TaskModuleResult start() {
        if (started) {
            return tick();
        }
        started = true;
        inFlight = true;
        return handle(context.containerInspections().startInspection(target));
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) {
            return start();
        }
        if (!inFlight) {
            return TaskModuleResult.succeeded("容器读取已完成");
        }
        return handle(context.containerInspections().pollInspection());
    }

    @Override
    public void requestStop() {
    }

    @Override
    public boolean safeToStop() {
        return !inFlight;
    }

    @Override
    public void cancel() {
        if (inFlight) {
            context.containerInspections().cancelInspection();
        }
        inFlight = false;
    }

    private TaskModuleResult handle(ContainerInspectionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        inFlight = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        context.workspace().recordContainerRead(target, result.itemIds());
        return TaskModuleResult.succeeded(result.message());
    }
}
