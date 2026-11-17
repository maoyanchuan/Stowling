package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DroppedItemCollectionEngine {
    interface Source {
        boolean dimensionMatches();

        List<DroppedItem> observe();
    }

    private static final int STABLE_CONFIRM_TICKS = 40;

    private final TaskExecutionContext context;
    private final Source source;
    private final Set<String> itemFilter;
    private final int timeoutSeconds;
    private final PlayerInventoryManager inventory;
    private final Clock clock;
    private Instant lastProgressAt;
    private DroppedItem current;
    private boolean navigationStarted;
    private boolean arrivedAtCurrent;
    private boolean stopping;
    private boolean pickupConfirmed;
    private boolean inventoryAvailable;
    private boolean drainChecked;
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
                new PlayerInventoryManager(() -> context.droppedItems()
                        .playerItemCounts()
                        .map(PlayerInventorySnapshot::new)),
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
        recordInventoryProgress();
        if (timedOut()) {
            return TaskModuleResult.failed("拾取掉落物超时");
        }
        if (current != null) {
            return continueCurrent(drops);
        }
        if (drops.isEmpty()) {
            return confirmEmpty();
        }
        current = drops.getFirst();
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
                || pickupConfirmed;
    }

    void cancel() {
        requestStop();
    }

    private TaskModuleResult tickWhileStopping() {
        stopping = true;
        context.navigation().cancel();
        recordInventoryProgress();
        if (current == null || pickupConfirmed || !arrivedAtCurrent) {
            current = null;
            return TaskModuleResult.succeeded("拾取已停止");
        }
        List<DroppedItem> drops = matches();
        DroppedItem observed = drops.stream()
                .filter(item -> item.entityId().equals(current.entityId()))
                .findFirst()
                .orElse(null);
        if (observed == null) {
            recordUnobservedPickup(current);
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
            recordUnobservedPickup(current);
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

    private void recordInventoryProgress() {
        PlayerInventoryManager.Observation observation =
                inventory.observe().orElse(null);
        if (observation == null) {
            return;
        }
        inventoryAvailable = true;
        PlayerInventoryDelta delta = observation.delta();
        if (observation.baseline() || !delta.changed()) {
            return;
        }
        int recorded = recordFilteredAdditions(delta);
        pickupConfirmed = pickupConfirmed || recorded > 0;
        resetStableConfirmation();
        lastProgressAt = clock.instant();
    }

    private int recordFilteredAdditions(PlayerInventoryDelta delta) {
        int recordedCount = 0;
        for (Map.Entry<String, Integer> entry : delta.added().entrySet()) {
            if (!TaskModuleSupport.itemMatches(
                    entry.getKey(),
                    itemFilter
            )) {
                continue;
            }
            context.workspace().recordCollected(entry.getKey(), entry.getValue());
            recordedCount = Math.addExact(recordedCount, entry.getValue());
        }
        return recordedCount;
    }

    private TaskModuleResult confirmEmpty() {
        if (stableTicks < STABLE_CONFIRM_TICKS) {
            stableTicks++;
            return TaskModuleResult.running(
                    "等待掉落物和背包稳定 " + stableTicks + "/"
                            + STABLE_CONFIRM_TICKS
            );
        }
        if (!drainChecked) {
            drainChecked = true;
            return TaskModuleResult.running("再次确认拾取区域为空");
        }
        return TaskModuleResult.succeeded("掉落物已拾取完毕，背包已稳定");
    }

    private void recordUnobservedPickup(DroppedItem pickup) {
        if (inventoryAvailable) {
            return;
        }
        context.workspace().recordCollected(
                pickup.itemId(),
                pickup.count()
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
