package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MarkerContainerInspectionAction {
    private static final int CONTAINER_NAVIGATION_RADIUS = 4;

    private final TaskExecutionContext context;
    private final List<MarkerContainerScanAction.Target> targets;
    private final Map<UUID, Map<BlockPosition, Set<String>>> cache;
    private int targetIndex;
    private MarkerContainerScanAction.Target activeTarget;
    private boolean navigationStarted;
    private boolean inspectionActive;

    MarkerContainerInspectionAction(
            TaskExecutionContext context,
            MarkerContainerScanAction.Plan plan
    ) {
        this.context = context;
        this.targets = List.copyOf(plan.targets());
        this.cache = new LinkedHashMap<>(plan.cache());
    }

    TaskModuleResult start() {
        return tick();
    }

    TaskModuleResult tick() {
        if (targetIndex >= targets.size()) {
            return TaskModuleResult.succeeded(
                    "分类容器索引读取完成，共 " + targets.size() + " 个容器"
            );
        }
        if (activeTarget == null) {
            activeTarget = targets.get(targetIndex);
        }
        NavigationStatus navigation = navigate();
        if (navigation == NavigationStatus.FAILED) {
            return TaskModuleResult.failed(
                    "无法前往分类标记容器 "
                            + activeTarget.target().position()
            );
        }
        if (navigation != NavigationStatus.ARRIVED) {
            return TaskModuleResult.waiting("正在前往分类标记容器");
        }
        ContainerInspectionResult result = inspect();
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        inspectionActive = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        store(activeTarget, result.itemIds());
        activeTarget = null;
        navigationStarted = false;
        targetIndex++;
        return targetIndex >= targets.size()
                ? tick()
                : TaskModuleResult.running("分类标记容器读取中");
    }

    void cancel() {
        context.containerInspections().cancelInspection();
        context.navigation().cancel();
    }

    Map<UUID, Map<BlockPosition, Set<String>>> cache() {
        return cache;
    }

    private NavigationStatus navigate() {
        if (!navigationStarted) {
            navigationStarted = true;
            return context.navigation().startNear(
                    activeTarget.target().position(),
                    CONTAINER_NAVIGATION_RADIUS
            );
        }
        return context.navigation().status(
                activeTarget.target().position(),
                CONTAINER_NAVIGATION_RADIUS
        );
    }

    private ContainerInspectionResult inspect() {
        ContainerInspectionPort inspections =
                context.containerInspections();
        if (!inspectionActive) {
            inspectionActive = true;
            return inspections.startInspection(activeTarget.target());
        }
        return inspections.pollInspection();
    }

    private void store(
            MarkerContainerScanAction.Target marker,
            Set<String> itemIds
    ) {
        for (UUID bindingId : marker.bindingIds()) {
            cache.get(bindingId).put(
                    marker.target().position(),
                    Set.copyOf(itemIds)
            );
        }
    }
}
