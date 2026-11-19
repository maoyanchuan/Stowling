package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class DroppedItemCollectionEngine {
    interface Source {
        boolean dimensionMatches();

        List<DroppedItem> observe();

        default boolean canPickUp(DroppedItem item) {
            return true;
        }
    }

    private static final int INVENTORY_SETTLE_TICKS = 20;

    private final TaskExecutionContext context;
    private final Source source;
    private final Set<String> itemFilter;
    private final int timeoutSeconds;
    private final PlayerInventoryManager inventory;
    private final Clock clock;
    private Instant lastProgressAt;
    private PlayerInventorySnapshot startInventory;
    private PlayerInventorySnapshot currentInventory;
    private DroppedItem current;
    private boolean navigationStarted;
    private boolean arrivedAtCurrent;
    private boolean stopping;
    private boolean pickupConfirmed;
    private boolean drainChecked;
    private boolean claimedExistingReceipts;
    private int stableTicks;
    private Set<DroppedItem> observedDrops = Set.of();

    DroppedItemCollectionEngine(
            TaskExecutionContext context,
            Source source,
            int timeoutSeconds,
            Set<String> itemFilter
    ) {
        this(
                context,
                source,
                timeoutSeconds,
                itemFilter,
                context.inventory(),
                Clock.systemUTC()
        );
    }

    DroppedItemCollectionEngine(
            TaskExecutionContext context,
            Source source,
            int timeoutSeconds,
            Set<String> itemFilter,
            PlayerInventoryManager inventory,
            Clock clock
    ) {
        this.context = context;
        this.source = source;
        this.timeoutSeconds = timeoutSeconds;
        this.itemFilter = Set.copyOf(itemFilter);
        this.inventory = inventory;
        this.clock = clock;
        this.lastProgressAt = clock.instant();
        context.workspace().inventoryAccount().beginCollection(itemFilter);
    }

    TaskModuleResult start() {
        return tick();
    }

    TaskModuleResult tick() {
        if (stopping) {
            return tickWhileStopping();
        }
        if (!source.dimensionMatches()) {
            return TaskModuleResult.failed("拾取区域维度不匹配");
        }
        List<DroppedItem> drops = matches();
        recordDropProgress(drops);
        PlayerInventoryManager.Observation observation = inventory.observe()
                .orElse(null);
        if (observation == null) {
            return TaskModuleResult.failed("无法创建任务前背包快照");
        }
        recordInventoryObservation(observation);
        recordCollectedFromSnapshot();
        List<DroppedItem> available = drops.stream().filter(source::canPickUp).toList();
        dev.stowling.v2.runtime.TaskTrace.event("DECISION", "pickup.capacity",
                "entities=" + drops.size() + "; pickable=" + available.size()
                        + "; carried=" + context.workspace().collectedItems()
                        + "; current=" + (current == null ? "none" : current.itemId()));
        if (!drops.isEmpty() && available.isEmpty()) {
            context.navigation().cancel();
            current = null;
            return context.workspace().collectedItems().isEmpty()
                    ? TaskModuleResult.failed("背包空间不足，没有可先存入缓存的任务物品，请腾出空间")
                    : TaskModuleResult.succeeded("背包已满，先将本批物品存入缓存后继续拾取");
        }
        if (timedOut()) {
            return TaskModuleResult.failed("拾取掉落物超时");
        }
        if (current != null && !source.canPickUp(current)) {
            context.navigation().cancel();
            current = null;
        }
        if (current != null) {
            return continueCurrent(drops);
        }
        if (drops.isEmpty()) {
            return confirmEmpty();
        }
        current = available.getFirst();
        navigationStarted = false;
        arrivedAtCurrent = false;
        pickupConfirmed = false;
        resetStableConfirmation();
        return tick();
    }

    void requestStop() {
        stopping = true;
        context.navigation().cancel();
    }

    boolean safeToStop() {
        return !stopping
                || current == null
                || !arrivedAtCurrent
                || pickupConfirmed
                || !source.canPickUp(current);
    }

    void cancel() {
        requestStop();
    }

    private TaskModuleResult tickWhileStopping() {
        stopping = true;
        context.navigation().cancel();
        PlayerInventoryManager.Observation observation = inventory.observe()
                .orElse(null);
        if (observation != null) {
            recordInventoryObservation(observation);
            recordCollectedFromSnapshot();
        }
        if (current == null || pickupConfirmed || !arrivedAtCurrent
                || !source.canPickUp(current)) {
            recordCollectedFromSnapshot();
            current = null;
            return TaskModuleResult.succeeded("拾取已停止");
        }
        List<DroppedItem> drops = matches();
        DroppedItem observed = drops.stream()
                .filter(item -> item.entityId().equals(current.entityId()))
                .findFirst()
                .orElse(null);
        if (observed == null) {
            recordCollectedFromSnapshot();
            current = null;
            return TaskModuleResult.succeeded("最后一次拾取已记账");
        }
        current = observed;
        return TaskModuleResult.waiting("等待最后一次拾取确认");
    }

    private TaskModuleResult continueCurrent(List<DroppedItem> drops) {
        DroppedItem observed = drops.stream()
                .filter(item -> item.entityId().equals(current.entityId()))
                .findFirst()
                .orElse(null);
        if (observed == null) {
            current = null;
            navigationStarted = false;
            arrivedAtCurrent = false;
            pickupConfirmed = false;
            resetStableConfirmation();
            return TaskModuleResult.running("已拾取一组掉落物");
        }
        current = observed;
        NavigationStatus status;
        if (!navigationStarted) {
            navigationStarted = true;
            status = context.navigation().start(observed.position());
        } else {
            status = context.navigation().status(observed.position(), 1);
        }
        return switch (status) {
            case ARRIVED -> {
                arrivedAtCurrent = true;
                yield TaskModuleResult.running(
                        "等待拾取 " + observed.itemId()
                );
            }
            case NAVIGATING -> TaskModuleResult.running("正在前往掉落物");
            case FAILED -> TaskModuleResult.failed("无法前往掉落物");
        };
    }

    private List<DroppedItem> matches() {
        return source.observe().stream()
                .filter(item -> TaskModuleSupport.itemMatches(
                        item.itemId(),
                        itemFilter
                ))
                .toList();
    }

    private void recordDropProgress(List<DroppedItem> drops) {
        Set<DroppedItem> observed = Set.copyOf(drops);
        if (observed.equals(observedDrops)) {
            return;
        }
        observedDrops = observed;
        resetStableConfirmation();
        lastProgressAt = clock.instant();
    }

    private void recordInventoryObservation(
            PlayerInventoryManager.Observation observation
    ) {
        PlayerInventorySnapshot snapshot = observation.snapshot();
        if (startInventory == null) {
            startInventory = snapshot;
            context.workspace().captureInitialInventory(
                    startInventory.itemCounts()
            );
            currentInventory = snapshot;
            context.workspace().observeInventory(snapshot.itemCounts());
            return;
        }
        if (snapshot.equals(currentInventory)) {
            return;
        }
        currentInventory = snapshot;
        pickupConfirmed = true;
        resetStableConfirmation();
        lastProgressAt = clock.instant();
    }

    private TaskModuleResult confirmEmpty() {
        if (stableTicks < INVENTORY_SETTLE_TICKS) {
            stableTicks++;
            return TaskModuleResult.running(
                    "等待拾取区域和背包稳定 " + stableTicks + "/"
                            + INVENTORY_SETTLE_TICKS
            );
        }
        if (!drainChecked) {
            drainChecked = true;
            return TaskModuleResult.running("再次确认拾取区域为空");
        }
        recordCollectedFromSnapshot();
        return TaskModuleResult.succeeded("掉落物已拾取完毕");
    }

    private void recordCollectedFromSnapshot() {
        if (startInventory == null || currentInventory == null) {
            return;
        }
        if (!claimedExistingReceipts) {
            inventory.claimUnexpected(context.workspace().inventoryAccount(), itemFilter);
            claimedExistingReceipts = true;
        } else {
            inventory.claimCollectionItems(context.workspace().inventoryAccount(), itemFilter);
        }
        context.workspace().observeInventory(
                currentInventory.itemCounts()
        );
    }

    private void resetStableConfirmation() {
        stableTicks = 0;
        drainChecked = false;
    }

    private boolean timedOut() {
        return timeoutSeconds > 0
                && Duration.between(lastProgressAt, clock.instant()).toSeconds()
                >= timeoutSeconds;
    }
}
