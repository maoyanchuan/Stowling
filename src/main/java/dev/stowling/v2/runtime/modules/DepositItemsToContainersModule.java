package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategory;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DepositItemsToContainersModule implements TaskModuleExecutor {
    private static final int CONTAINER_NAVIGATION_RADIUS = 4;

    private final TaskExecutionContext context;
    private final Map<String, Integer> remaining;
    private final List<StorageTarget> targets;
    private final String unavailableMessage;
    private int targetIndex;
    private StorageTarget activeTarget;
    private Map<String, Integer> activeRequest = Map.of();
    private boolean navigationStarted;
    private boolean transferActive;

    DepositItemsToContainersModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        this.remaining = new LinkedHashMap<>(
                context.workspace().collectedItems()
        );
        ContainerScanResult scan = context.workspace().containerScan()
                .orElse(null);
        Map<ContainerTarget, String> categories = categories();
        this.targets = scan == null
                ? List.of()
                : buildTargets(scan.containers(), categories, remaining);
        this.unavailableMessage = scan == null
                ? "缺少上一次容器扫描结果"
                : scan.succeeded() ? "" : scan.message();
    }

    @Override
    public TaskModuleResult start() {
        if (remaining.isEmpty()) {
            return TaskModuleResult.succeeded("没有需要存入的物品");
        }
        if (!unavailableMessage.isEmpty()) {
            return TaskModuleResult.failed(unavailableMessage);
        }
        if (targets.isEmpty()) {
            return TaskModuleResult.failed("扫描结果中没有可用容器");
        }
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (remaining.isEmpty()) {
            return TaskModuleResult.succeeded("拾取物品已全部存入容器");
        }
        if (transferActive) {
            return pollTransfer();
        }
        if (activeTarget == null && !selectNextTarget()) {
            return TaskModuleResult.failed(
                    "所有已扫描容器均无足够空间"
            );
        }
        NavigationStatus status;
        if (!navigationStarted) {
            navigationStarted = true;
            status = context.navigation().startNear(
                    activeTarget.target().position(),
                    CONTAINER_NAVIGATION_RADIUS
            );
        } else {
            status = context.navigation().status(
                    activeTarget.target().position(),
                    CONTAINER_NAVIGATION_RADIUS
            );
        }
        return switch (status) {
            case ARRIVED -> startTransfer();
            case NAVIGATING -> TaskModuleResult.running("正在前往存储容器");
            case FAILED -> {
                moveToNextTarget();
                yield TaskModuleResult.running("容器不可达，尝试下一个");
            }
        };
    }

    @Override
    public void cancel() {
        context.containerTransfers().cancelDeposit();
        context.navigation().cancel();
    }

    private TaskModuleResult startTransfer() {
        transferActive = true;
        ContainerTransferResult result = context.containerTransfers()
                .startDeposit(activeTarget.target(), activeRequest);
        return handleTransferResult(result);
    }

    private TaskModuleResult pollTransfer() {
        return handleTransferResult(
                context.containerTransfers().pollDeposit()
        );
    }

    private TaskModuleResult handleTransferResult(
            ContainerTransferResult result
    ) {
        Map<String, Integer> before = Map.copyOf(remaining);
        remaining.clear();
        remaining.putAll(result.remaining());
        recordTransferred(before, remaining);
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        transferActive = false;
        if (result.state() == ContainerTransferState.SUCCEEDED
                && remaining.isEmpty()) {
            return TaskModuleResult.succeeded("拾取物品已全部存入容器");
        }
        context.containerTransfers().cancelDeposit();
        moveToNextTarget();
        return TaskModuleResult.running(
                result.message() + "，尝试下一个容器"
        );
    }

    private boolean selectNextTarget() {
        while (targetIndex < targets.size()) {
            StorageTarget candidate = targets.get(targetIndex);
            Map<String, Integer> request = requestFor(candidate);
            if (!request.isEmpty()) {
                activeTarget = candidate;
                activeRequest = request;
                navigationStarted = false;
                return true;
            }
            targetIndex++;
        }
        return false;
    }

    private Map<String, Integer> requestFor(StorageTarget target) {
        if (!target.exactCategory()) {
            return Map.copyOf(remaining);
        }
        int count = remaining.getOrDefault(target.categoryItemId(), 0);
        return count <= 0
                ? Map.of()
                : Map.of(target.categoryItemId(), count);
    }

    private void moveToNextTarget() {
        targetIndex++;
        activeTarget = null;
        activeRequest = Map.of();
        navigationStarted = false;
    }

    private Map<ContainerTarget, String> categories() {
        Map<ContainerTarget, String> result = new LinkedHashMap<>();
        ContainerCategoryResult categoryResult = context.workspace()
                .containerCategories()
                .orElse(null);
        if (categoryResult == null || !categoryResult.succeeded()) {
            return result;
        }
        for (ContainerCategory category : categoryResult.categories()) {
            result.put(category.container(), category.itemId());
        }
        return result;
    }

    private static List<StorageTarget> buildTargets(
            List<ContainerTarget> containers,
            Map<ContainerTarget, String> categories,
            Map<String, Integer> remaining
    ) {
        List<StorageTarget> exact = new ArrayList<>();
        List<StorageTarget> unclassified = new ArrayList<>();
        List<StorageTarget> fallback = new ArrayList<>();
        for (ContainerTarget container : containers) {
            String category = categories.get(container);
            if (category == null) {
                unclassified.add(new StorageTarget(container, "", false));
            } else if (remaining.containsKey(category)) {
                exact.add(new StorageTarget(container, category, true));
            } else {
                fallback.add(new StorageTarget(container, category, false));
            }
        }
        List<StorageTarget> result = new ArrayList<>();
        result.addAll(exact);
        result.addAll(unclassified);
        result.addAll(fallback);
        return List.copyOf(result);
    }

    private void recordTransferred(
            Map<String, Integer> before,
            Map<String, Integer> after
    ) {
        Map<String, Integer> itemIds = new LinkedHashMap<>(before);
        after.keySet().forEach(itemId -> itemIds.putIfAbsent(itemId, 0));
        for (Map.Entry<String, Integer> entry : itemIds.entrySet()) {
            int moved = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
            if (moved > 0) {
                context.workspace().removeCollected(entry.getKey(), moved);
            }
        }
    }

    private record StorageTarget(
            ContainerTarget target,
            String categoryItemId,
            boolean exactCategory
    ) {
    }
}
