package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;

final class MoveToCoordinateModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CoordinatePoint target;
    private final BlockPosition position;
    private final Integer arrivalRadius;
    private boolean navigationStarted;

    MoveToCoordinateModule(TaskExecutionContext context, TaskStep step) {
        this(context, step, null);
    }

    MoveToCoordinateModule(
            TaskExecutionContext context,
            TaskStep step,
            Integer arrivalRadius
    ) {
        this.context = context;
        this.target = TaskModuleSupport.coordinate(context, step);
        this.position = new BlockPosition(target.x(), target.y(), target.z());
        this.arrivalRadius = arrivalRadius == null
                ? target.radius()
                : arrivalRadius;
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (!TaskModuleSupport.dimensionMatches(context, target)) {
            return TaskModuleResult.failed(
                    "无法前往 " + target.name() + "：维度不匹配"
            );
        }
        NavigationStatus status;
        if (!navigationStarted) {
            navigationStarted = true;
            status = arrivalRadius == null
                    ? context.navigation().start(position)
                    : context.navigation().startNear(position, arrivalRadius);
        } else {
            status = context.navigation().status(position, arrivalRadius);
        }
        return switch (status) {
            case ARRIVED -> TaskModuleResult.succeeded("已到达 " + target.name());
            case NAVIGATING -> TaskModuleResult.running("正在前往 " + target.name());
            case FAILED -> TaskModuleResult.failed("无法前往 " + target.name());
        };
    }

    @Override
    public void cancel() {
        context.navigation().cancel();
    }
}
