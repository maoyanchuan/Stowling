package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class MoveToCoordinateModule implements TaskModuleExecutor {
    private static final int CONTAINER_NAVIGATION_RADIUS = 4;

    private final TaskExecutionContext context;
    private final ResourceReference targetReference;
    private CoordinatePoint coordinate;
    private ContainerTarget container;
    private BlockPosition position;
    private String targetName = "目标";
    private Integer arrivalRadius;
    private boolean navigationStarted;
    private boolean stopped;

    MoveToCoordinateModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.targetReference = ResourceReferenceSupport.optionalReference(
                step,
                TaskModuleIds.TARGET_REF,
                ResourceType.COORDINATE,
                TaskModuleIds.COORDINATE_ID
        );
        resolveTarget(step);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (stopped) {
            return TaskModuleResult.succeeded("寻路已停止");
        }
        if (coordinate != null
                && !TaskModuleSupport.dimensionMatches(context, coordinate)) {
            return TaskModuleResult.failed(
                    "无法前往 " + coordinate.name() + "：维度不匹配"
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
            case ARRIVED -> TaskModuleResult.succeeded("已到达 " + targetName);
            case NAVIGATING -> TaskModuleResult.running("正在前往 " + targetName);
            case FAILED -> TaskModuleResult.failed("无法前往 " + targetName);
        };
    }

    @Override
    public void requestStop() {
        stopped = true;
        context.navigation().cancel();
    }

    @Override
    public void cancel() {
        stopped = true;
        context.navigation().cancel();
    }

    private void resolveTarget(TaskStep step) {
        if (targetReference == null) {
            int x = manualCoordinate(step, TaskModuleIds.X);
            int y = manualCoordinate(step, TaskModuleIds.Y);
            int z = manualCoordinate(step, TaskModuleIds.Z);
            position = new BlockPosition(x, y, z);
            targetName = "手动坐标 (" + x + ", " + y + ", " + z + ")";
            return;
        }
        if (targetReference.type() == ResourceType.COORDINATE) {
            coordinate = ResourceReferenceSupport.coordinate(
                    context,
                    targetReference
            );
            position = new BlockPosition(
                    coordinate.x(),
                    coordinate.y(),
                    coordinate.z()
            );
            targetName = coordinate.name();
            arrivalRadius = coordinate.radius();
            return;
        }
        if (targetReference.type() != ResourceType.OUTPUT) {
            throw new IllegalArgumentException(
                    "MOVE_TO_COORDINATE only accepts coordinate or output refs"
            );
        }
        container = ResourceReferenceSupport.output(
                context.workspace(),
                targetReference
        );
        position = container.position();
        targetName = container.type();
        arrivalRadius = CONTAINER_NAVIGATION_RADIUS;
    }

    private static int manualCoordinate(TaskStep step, String key) {
        return Integer.parseInt(ResourceReferenceSupport.parameter(step, key));
    }
}
