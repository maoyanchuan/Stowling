package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.TaskStep;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects a full or complete batch, unloads unprotected cargo, then checks the area again. */
final class PickupCacheCycle implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final TaskStep step;
    private final ResourceReference cache;
    private final Set<ContainerTarget> unavailable = new HashSet<>();
    private DroppedItemCollectionEngine collection;
    private List<ContainerTarget> containers;
    private ContainerTarget target;
    private Map<String, Integer> requested = Map.of();
    private boolean inFlight;
    private boolean stopping;
    private TaskModuleResult terminal;

    PickupCacheCycle(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.step = step;
        cache = ResourceReference.parse(step.parameters().get(ConfigValidator.CACHE_LOCATION_REF));
        collection = CollectDroppedItemsModule.createEngine(context, step, true);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (terminal != null) return terminal;
        if (collection != null) return collect();
        if (inFlight) return confirmDeposit(context.containerTransfers().pollDeposit());
        if (stopping) return finish("拾取与缓存存放已安全停止", false);
        if (!refreshCargo()) return finish("无法读取缓存存入前的真实背包", true);
        if (context.workspace().managedItems().isEmpty()) {
            if (pendingPickups()) return TaskModuleResult.waiting("等待最后的拾取回执与背包同步");
            context.containerTransfers().finishPickupCacheDeposit();
            collection = CollectDroppedItemsModule.createEngine(context, step, true);
            return TaskModuleResult.running("本轮未保护物品已存完，重新检测掉落物");
        }
        if (target == null) {
            target = containers.stream().filter(candidate -> !unavailable.contains(candidate))
                    .min(Comparator.<ContainerTarget>comparingInt(candidate ->
                            context.navigation().isWithinInteractionRange(candidate.position(),
                                    candidate.standingArea(), false) ? 0 : 1)
                            .thenComparing(context.storageAutomation().pickupCacheOrder()))
                    .orElse(null);
            if (target == null) {
                return finish("缓存容量不足或容器不可用，未存物品保留在背包，已停止继续拾取", true);
            }
        }
        requested = context.workspace().managedItems();
        inFlight = true;
        return confirmDeposit(context.containerTransfers().startPickupCacheDeposit(
                target, requested));
    }

    private TaskModuleResult collect() {
        TaskModuleResult result = collection.tick();
        if (stopping && collection.safeToStop()) {
            return finish("拾取已停止，交由任务收尾处理背包", false);
        }
        if (result.state() == TaskModuleState.FAILED) return finish(result.message(), true);
        if (result.state() != TaskModuleState.SUCCEEDED) return result;
        if (!refreshCargo()) return finish("无法读取拾取后的真实背包", true);
        if (context.workspace().managedItems().isEmpty()) {
            if (pendingPickups()) return TaskModuleResult.waiting("等待拾取回执与背包同步");
            return finish("掉落物已全部消失，未保护物品已全部存入缓存", false);
        }
        collection = null;
        if (containers == null) {
            var scan = CacheLocations.scan(context, cache, TaskModuleSupport.containerTypes(step),
                    TaskModuleSupport.integerParameter(step, TaskModuleIds.MAX_VOLUME,
                            TaskModuleSupport.DEFAULT_SCAN_VOLUME), false);
            if (!scan.succeeded()) return finish(scan.message(), true);
            containers = scan.containers();
            context.workspace().refreshContainerScan(cache.uuid(), scan);
        }
        unavailable.clear();
        target = null;
        return TaskModuleResult.running("本轮拾取结束，准备存入全部未保护物品");
    }

    private boolean refreshCargo() {
        if (context.inventory().observe().isEmpty()) return false;
        context.inventory().claimAllUnprotectedForCollection(context.workspace().inventoryAccount());
        return true;
    }

    private boolean pendingPickups() {
        return context.inventory().pendingPickupCount(context.workspace().inventoryAccount()) > 0;
    }

    private TaskModuleResult confirmDeposit(ContainerTransferResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        inFlight = false;
        Map<String, Integer> deposited = new LinkedHashMap<>();
        requested.forEach((key, count) -> {
            int moved = count - result.remaining().getOrDefault(key, 0);
            if (moved > 0) deposited.put(key, moved);
        });
        context.workspace().consumeDeposit("managed", requested, result.remaining());
        context.workspace().recordCacheTransfer(target, deposited, false);
        context.workspace().progress().recordDelivery(target.position(), deposited);
        if (!deposited.isEmpty()) {
            context.workspace().recordCacheUpdate(cache.uuid(),
                    deposited.values().stream().mapToInt(Integer::intValue).sum());
        }
        if (result.state() == ContainerTransferState.FAILED || !result.remaining().isEmpty()
                || deposited.isEmpty()) {
            unavailable.add(target);
            target = null;
        }
        return TaskModuleResult.running("本批存入已确认，继续核对新增拾取与剩余未保护物品");
    }

    private TaskModuleResult finish(String message, boolean failed) {
        context.navigation().cancel();
        context.containerTransfers().finishPickupCacheDeposit();
        terminal = failed ? TaskModuleResult.failed(message) : TaskModuleResult.succeeded(message);
        return terminal;
    }

    @Override
    public void requestStop() {
        stopping = true;
        if (collection != null) collection.requestStop();
    }

    @Override
    public boolean safeToStop() {
        return !inFlight && (collection == null || collection.safeToStop());
    }

    @Override
    public void cancel() {
        requestStop();
        if (safeToStop()) finish("拾取与缓存存放已取消", false);
    }
}
