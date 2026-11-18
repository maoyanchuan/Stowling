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
        inFlight = true;
        return handle(classificationBatch
                ? context.containerWithdrawals().startClassificationBatch(target, matchingItemIds,
                        fullBoxCategories())
                : context.containerWithdrawals().startWithdrawal(target, matchingItemIds));
    }

    private Set<String> fullBoxCategories() {
        Set<String> categories = new LinkedHashSet<>();
        context.workspace().acceptedItemsByContainer().forEach((container, accepted) -> {
            if (container.storagePolicy().mode() != StorageMode.NORMAL
                    && !container.storagePolicy().allowPartialBoxes()) {
                accepted.stream().map(ItemIdentity::category)
                        .forEach(categories::add);
            }
        });
        return Set.copyOf(categories);
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) {
            return start();
        }
        if (!inFlight) {
            return TaskModuleResult.succeeded("取出操作已完成");
        }
        return handle(context.containerWithdrawals().pollWithdrawal());
    }

    @Override
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    public boolean safeToStop() {
        return !inFlight;
    }

    @Override
    public void cancel() {
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

