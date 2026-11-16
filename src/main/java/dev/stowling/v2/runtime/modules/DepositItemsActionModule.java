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
        if (requested.isEmpty()) {
            return TaskModuleResult.succeeded("没有需要存入的物品");
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
        if (!inFlight) {
            return TaskModuleResult.succeeded("存入操作已完成");
        }
        return handle(context.containerTransfers().pollDeposit());
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
        inFlight = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        if (stopRequested) {
            return TaskModuleResult.succeeded("存入已确认，可安全停止");
        }
        return TaskModuleResult.succeeded(result.message());
    }

    private static Map<String, Integer> filterForTarget(
            TaskExecutionContext context,
            ContainerTarget target,
            ResourceReference reference,
            String source,
            Map<String, Integer> items
    ) {
        if (!"carried".equals(source)
                || reference.type() != ResourceType.OUTPUT
                || !"storage".equals(reference.key())) {
            return items;
        }
        Set<String> accepted = context.workspace()
                .acceptedItemsByContainer()
                .getOrDefault(target, Set.of());
        Map<String, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (accepted.contains(entry.getKey())) {
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
