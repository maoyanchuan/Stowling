package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class DepositItemsActionModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private final ResourceReference targetReference;
    private final String itemSource;
    private final Map<String, Integer> requested;
    private final boolean retryOnFailure;
    private TaskModuleExecutor packing;
    private boolean started;
    private boolean inFlight;
    private boolean stopRequested;

    DepositItemsActionModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        this.targetReference = targetReference(step);
        this.target = target(context, targetReference);
        this.itemSource = itemSource(step);
        if ("collected".equals(itemSource) && context.workspace().inventoryAccount().collecting()) {
            if (context.inventory().observe().isEmpty()) {
                throw new IllegalStateException("无法读取缓存存入前的真实背包");
            }
            context.inventory().claimCollectionItems(context.workspace().inventoryAccount(),
                    Set.of());
        }
        this.retryOnFailure = TaskModuleSupport.booleanParameter(
                step,
                TaskModuleIds.RETRY_ON_FAILURE,
                false
        );
        this.requested = filterForTarget(
                context,
                target,
                targetReference,
                itemSource,
                itemsForSource(context, itemSource)
        );
    }

    @Override
    public TaskModuleResult start() {
        if (started) {
            return tick();
        }
        started = true;
        if (context.workspace().isRejectedStorage(target)) {
            return TaskModuleResult.succeeded("已跳过无法到达的分类箱 " + target.position());
        }
        if (requested.isEmpty()) {
            if (retryOnFailure) {
                context.workspace().markTargetUnavailable(target);
                return TaskModuleResult.succeeded(
                        "目标容器不接受当前物品，尝试下一个同类容器"
                );
            }
            return TaskModuleResult.succeeded("没有需要存入的物品");
        }
        if (!context.workspace().selectedStorageUsesCacheFallback()
                && target.storagePolicy().mode() != dev.stowling.v2.model.StorageMode.NORMAL) {
            packing = context.storageAutomation().deposit(context, target, requested);
            return handlePacking(packing.start());
        }
        inFlight = true;
        return handle(context.containerTransfers().startDeposit(
                target,
                requested
        ));
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) {
            return start();
        }
        if (packing != null) {
            return handlePacking(packing.tick());
        }
        if (!inFlight) {
            return TaskModuleResult.succeeded("存入操作已完成");
        }
        return handle(context.containerTransfers().pollDeposit());
    }

    private TaskModuleResult handlePacking(TaskModuleResult result) {
        if (result.state() == TaskModuleState.FAILED && retryOnFailure
                && context.workspace().isClassificationStorage(target) && packing.safeToStop()
                && !context.storageAutomation().hasTemporaryResources(
                        context.workspace().inventoryAccount().ownerId())) {
            context.workspace().markTargetUnavailable(target);
            if (!itemsForSource(context, itemSource).isEmpty()) {
                return TaskModuleResult.succeeded(result.message() + "；尝试同组下一箱");
            }
        }
        return result;
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        if (packing != null) {
            packing.requestStop();
        }
    }

    @Override
    public boolean safeToStop() {
        return packing == null ? !inFlight : packing.safeToStop();
    }

    @Override
    public void cancel() {
        if (packing != null) {
            packing.cancel();
        }
        if (inFlight) {
            context.containerTransfers().cancelDeposit();
        }
        inFlight = false;
    }

    private TaskModuleResult handle(ContainerTransferResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        context.workspace().consumeDeposit(
                itemSource,
                requested,
                result.remaining()
        );
        Map<String, Integer> deposited = new LinkedHashMap<>();
        requested.forEach((key, count) -> {
            int moved = count - result.remaining().getOrDefault(key, 0);
            if (moved > 0) deposited.put(key, moved);
        });
        context.workspace().recordCacheTransfer(target, deposited, false);
        context.workspace().progress().recordDelivery(target.position(), deposited);
        recordCacheUpdate(result.remaining());
        if (retryOnFailure && "carried".equals(itemSource)
                && targetReference.type() == ResourceType.OUTPUT
                && "storage".equals(targetReference.key())
                && context.workspace().selectedStorageUsesCacheFallback()) {
            context.workspace().deferReturnedCacheItems(requested, result.remaining());
        }
        inFlight = false;
        if (result.state() == ContainerTransferState.SUCCEEDED
                && !madeProgress(requested, result.remaining())) {
            context.workspace().markTargetUnavailable(target);
        }
        if (result.state() == ContainerTransferState.FAILED) {
            context.workspace().markTargetUnavailable(target);
            if (retryOnFailure && !itemsForSource(context, itemSource).isEmpty()) {
                return TaskModuleResult.succeeded(
                        "目标容器已满或不可用，尝试下一个同类容器"
                );
            }
            return TaskModuleResult.failed(result.message());
        }
        if (stopRequested) {
            return TaskModuleResult.succeeded("存入已确认，可安全停止");
        }
        return TaskModuleResult.succeeded(result.message());
    }

    private static boolean madeProgress(
            Map<String, Integer> requested,
            Map<String, Integer> remaining
    ) {
        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            if (remaining.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> filterForTarget(
            TaskExecutionContext context,
            ContainerTarget target,
            ResourceReference reference,
            String source,
            Map<String, Integer> items
    ) {
        if ("inventory".equals(source)
                || reference.type() != ResourceType.OUTPUT
                || !"storage".equals(reference.key())
                || context.workspace().selectedStorageUsesCacheFallback()) {
            return items;
        }
        Set<String> accepted = context.workspace()
                .acceptedItemsByContainer()
                .getOrDefault(target, Set.of());
        Map<String, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (dev.stowling.automation.ItemIdentity.matches(entry.getKey(), accepted)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(filtered);
    }

    private static String itemSource(TaskStep step) {
        String value = ResourceReferenceSupport.optionalParameter(
                step,
                TaskModuleIds.ITEM_SOURCE
        );
        return value == null ? "collected" : value;
    }

    private void recordCacheUpdate(Map<String, Integer> remaining) {
        if (!"collected".equals(itemSource)
                || targetReference.type() != ResourceType.OUTPUT
                || !"container".equals(targetReference.key())) {
            return;
        }
        int deposited = 0;
        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            int left = remaining.getOrDefault(entry.getKey(), 0);
            deposited += Math.max(0, entry.getValue() - left);
        }
        final int totalDeposited = deposited;
        if (totalDeposited > 0) {
            context.workspace().containerScanRegionId().ifPresent(
                    regionId -> context.workspace().recordCacheUpdate(
                            regionId,
                            totalDeposited
                    )
            );
        }
    }

    private static Map<String, Integer> itemsForSource(
            TaskExecutionContext context,
            String source
    ) {
        if ("inventory".equals(source)) {
            return context.droppedItems().playerItemCounts()
                    .map(LinkedHashMap::new)
                    .map(Map::copyOf)
                    .orElseThrow(() -> new IllegalStateException(
                            "当前环境无法读取玩家背包"
                    ));
        }
        if ("collected".equals(source)
                || "carried".equals(source)
                || "managed".equals(source)) {
            return context.workspace().itemsForSource(source);
        }
        throw new IllegalArgumentException(
                "unsupported item source: " + source
        );
    }

    private static ResourceReference targetReference(TaskStep step) {
        return ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.TARGET_REF,
                ResourceType.OUTPUT
        );
    }

    private static ContainerTarget target(
            TaskExecutionContext context,
            ResourceReference reference
    ) {
        if (reference.type() == ResourceType.OUTPUT) {
            return ResourceReferenceSupport.output(
                    context.workspace(),
                    reference
            );
        }
        if (reference.type() == ResourceType.COORDINATE) {
            CoordinatePoint coordinate = ResourceReferenceSupport.coordinate(
                    context,
                    reference
            );
            BlockPosition position = new BlockPosition(
                    coordinate.x(),
                    coordinate.y(),
                    coordinate.z()
            );
            return new ContainerTarget(position, "container");
        }
        throw new IllegalArgumentException(
                "DEPOSIT_ITEMS_TO_CONTAINERS target must be a container output"
        );
    }
}
