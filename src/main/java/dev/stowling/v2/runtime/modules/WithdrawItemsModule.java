package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.TaskStep;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class WithdrawItemsModule implements TaskModuleExecutor {
    private static final String CLASSIFIED = "classified";

    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private final Set<String> matchingItemIds;
    private final boolean classificationBatch;
    private TaskModuleExecutor unpacking;
    private Map<String, Integer> beforeUnpacking = Map.of();
    private Set<String> beforeUnpackingCategories = Set.of();
    private boolean unpackingStarted;
    private boolean started;
    private boolean inFlight;
    private boolean stopRequested;

    WithdrawItemsModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.target = target(context, step);
        this.matchingItemIds = matchingItems(context, step, target);
        this.classificationBatch = context.workspace().cacheContainers().contains(target)
                && !context.workspace().acceptedItemsByContainer().isEmpty();
    }

    @Override
    public TaskModuleResult start() {
        if (started) {
            return tick();
        }
        started = true;
        if (classificationBatch) {
            beforeUnpacking = context.workspace().carriedItems();
            beforeUnpackingCategories = context.workspace().remainingCacheCategories(target);
            unpacking = context.storageAutomation().unpackMixedBox(context, target).orElse(null);
            if (unpacking != null) {
                unpackingStarted = true;
                return handleUnpacking(unpacking.start());
            }
        }
        return startWithdrawal();
    }

    private TaskModuleResult startWithdrawal() {
        Set<String> selected = classificationBatch
                ? context.workspace().remainingCacheCategories(target) : matchingItemIds;
        if (classificationBatch && context.workspace().cacheContents(target)
                .filter(items -> items.keySet().stream()
                        .noneMatch(key -> ItemIdentity.matches(key, selected))).isPresent()) {
            context.workspace().setCacheHasMore(false);
            return TaskModuleResult.succeeded("缓存已无可取出的分类物品");
        }
        inFlight = true;
        return handle(classificationBatch
                ? context.containerWithdrawals().startClassificationBatch(target, selected,
                        fullBoxCategories(), context.workspace()
                                .classificationItemOrder(selected))
                : context.containerWithdrawals().startWithdrawal(target, matchingItemIds));
    }

    private TaskModuleResult handleUnpacking(TaskModuleResult result) {
        if (result.state() != TaskModuleState.SUCCEEDED) {
            return result;
        }
        unpacking = null;
        if (!beforeUnpacking.equals(context.workspace().carriedItems())) {
            context.workspace().resetStorageSelection();
            context.workspace().setCacheHasMore(true);
            return TaskModuleResult.succeeded("缓存潜影盒与散货已组成批次，按仓储排版分类");
        }
        Set<String> remaining = context.workspace().remainingCacheCategories(target);
        if (!stopRequested && !remaining.equals(beforeUnpackingCategories)) {
            dev.stowling.v2.runtime.TaskTrace.event("DECISION", "cache.nextPackingCategory",
                    "已合并分类留在缓存，继续处理剩余分类；source=" + target.position()
                            + "; remaining=" + remaining, 0, false);
            beforeUnpacking = context.workspace().carriedItems();
            beforeUnpackingCategories = remaining;
            unpacking = context.storageAutomation().unpackMixedBox(context, target).orElse(null);
            if (unpacking != null) {
                unpackingStarted = false;
                return TaskModuleResult.waiting("当前分类已合并，正在处理下一个分类");
            }
        }
        return stopRequested ? TaskModuleResult.succeeded("拆盒已确认，可安全停止")
                : startWithdrawal();
    }

    private Set<String> fullBoxCategories() {
        Set<String> categories = new LinkedHashSet<>();
        Set<String> flexible = new LinkedHashSet<>();
        context.workspace().acceptedItemsByContainer().forEach((container, accepted) -> {
            Set<String> destination = container.storagePolicy().mode() == StorageMode.BOX_ONLY
                    && !container.storagePolicy().allowPartialBoxes()
                    ? categories : flexible;
            accepted.stream().map(ItemIdentity::category).forEach(destination::add);
        });
        categories.removeAll(flexible);
        return Set.copyOf(categories);
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) {
            return start();
        }
        if (unpacking != null) {
            if (!unpackingStarted) {
                unpackingStarted = true;
                return handleUnpacking(unpacking.start());
            }
            return handleUnpacking(unpacking.tick());
        }
        if (!inFlight) {
            return TaskModuleResult.succeeded("取出操作已完成");
        }
        return handle(context.containerWithdrawals().pollWithdrawal());
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        if (unpacking != null) {
            unpacking.requestStop();
        }
    }

    @Override
    public boolean safeToStop() {
        return unpacking == null ? !inFlight : unpacking.safeToStop();
    }

    @Override
    public void cancel() {
        if (unpacking != null) {
            unpacking.cancel();
        }
        if (inFlight) {
            context.containerWithdrawals().cancelWithdrawal();
        }
        inFlight = false;
    }

    private TaskModuleResult handle(ContainerWithdrawalResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        applyWithdrawn(result.withdrawn());
        context.workspace().setCacheHasMore(result.matchingItemsRemain()
                && (!classificationBatch || !result.withdrawn().isEmpty()));
        inFlight = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        if (stopRequested) {
            return TaskModuleResult.succeeded("取出已确认，可安全停止");
        }
        return matchingItemIds.isEmpty()
                ? TaskModuleResult.succeeded("没有需要取出的物品")
                : TaskModuleResult.succeeded(result.message());
    }

    private void applyWithdrawn(Map<String, Integer> withdrawn) {
        context.workspace().recordCacheTransfer(target, withdrawn, true);
        context.inventory().recordWithdrawn(context.workspace().inventoryAccount(), withdrawn);
        if (!withdrawn.isEmpty()) {
            context.workspace().resetStorageSelection();
        }
    }

    private static Set<String> matchingItems(
            TaskExecutionContext context,
            TaskStep step,
            ContainerTarget target
    ) {
        String source = ResourceReferenceSupport.optionalParameter(
                step,
                TaskModuleIds.ITEM_SOURCE
        );
        if (source == null || source.isBlank() || CLASSIFIED.equals(source)) {
            return context.workspace().remainingCacheCategories(target);
        }
        throw new IllegalArgumentException(
                "WITHDRAW_ITEMS supports itemSource=classified"
        );
    }

    private static ContainerTarget target(
            TaskExecutionContext context,
            TaskStep step
    ) {
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.TARGET_REF,
                ResourceType.OUTPUT
        );
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
                "WITHDRAW_ITEMS target must be a container output"
        );
    }
}

