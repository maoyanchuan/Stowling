package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerApproach;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class MoveToCoordinateModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final ResourceReference targetReference;
    private CoordinatePoint coordinate;
    private ContainerTarget container;
    private ContainerApproach approach;
    private BlockPosition position;
    private String targetName = "目标";
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
        if (container != null && targetReference.type() == ResourceType.OUTPUT
                && "storage".equals(targetReference.key())
                && !context.workspace().selectedStorageUsesCacheFallback()
                && context.workspace().isClassificationStorage(container)
                && container.storagePolicy().mode() == dev.stowling.v2.model.StorageMode.BOX_ONLY) {
            return TaskModuleResult.succeeded("纯盒模式先补盒，存入时再前往对应储存位置");
        }
        if (coordinate != null
                && !TaskModuleSupport.dimensionMatches(context, coordinate)) {
            return TaskModuleResult.failed(
                    "无法前往 " + coordinate.name() + "：维度不匹配"
            );
        }
        if (isAtContainer()) {
            return TaskModuleResult.succeeded(
                    "已在容器交互范围内 " + position
            );
        }
        NavigationStatus status;
        if (approach != null) {
            status = approach.tick();
        } else if (!navigationStarted) {
            navigationStarted = true;
            status = context.navigation().start(position);
        } else {
            status = context.navigation().status(position, 0);
        }
        return switch (status) {
            case ARRIVED -> TaskModuleResult.succeeded("已到达 " + targetName);
            case NAVIGATING -> TaskModuleResult.running("正在前往 " + targetName);
            case FAILED -> isAtContainer()
                    ? TaskModuleResult.succeeded(
                            "已在容器交互范围内 " + position
                    )
                    : unreachable();
        };
    }

    private boolean isAtContainer() {
        if (container == null || approach == null || container.partner() != null) {
            return false;
        }
        if (!context.navigation().isWithinInteractionRange(position, container.standingArea(),
                true, container.classificationStanding())) {
            return false;
        }
        approach.cancel();
        return true;
    }

    private TaskModuleResult unreachable() {
        if (container != null && "storage".equals(targetReference.key())
                && context.workspace().isClassificationStorage(container)) {
            context.workspace().markTargetUnavailable(container);
            return TaskModuleResult.succeeded("无法前往分类箱 " + position + "，尝试同组下一箱");
        }
        return TaskModuleResult.failed("无法前往 " + targetName + " " + position);
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
            if (Boolean.parseBoolean(step.parameters().get(ConfigValidator.INTERACT_WITH_TARGET))) {
                container = new ContainerTarget(position, targetName);
                approach = new ContainerApproach(context.navigation(), null, container.entrances());
            }
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
        approach = new ContainerApproach(context.navigation(), container.standingArea(),
                container.entrances(), true, false, container.classificationStanding());
    }

    private static int manualCoordinate(TaskStep step, String key) {
        return Integer.parseInt(ResourceReferenceSupport.parameter(step, key));
    }
}
