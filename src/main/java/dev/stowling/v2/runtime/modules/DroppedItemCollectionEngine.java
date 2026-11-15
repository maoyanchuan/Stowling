package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.NavigationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class DroppedItemCollectionEngine {
    interface Source {
        boolean dimensionMatches();

        List<DroppedItem> observe();
    }

    private static final int EMPTY_CONFIRM_TICKS = 10;

    private final TaskExecutionContext context;
    private final Source source;
    private final Set<String> itemFilter;
    private final int timeoutSeconds;
    private final Instant startedAt = Instant.now();
    private DroppedItem current;
    private boolean navigationStarted;
    private int emptyTicks;
    private Optional<Map<String, Integer>> previousInventory = Optional.empty();
    private boolean inventoryTracking;

    DroppedItemCollectionEngine(
            TaskExecutionContext context,
            Source source,
            int timeoutSeconds,
            Set<String> itemFilter
    ) {
        this.context = context;
        this.source = source;
        this.timeoutSeconds = timeoutSeconds;
        this.itemFilter = Set.copyOf(itemFilter);
    }

    TaskModuleResult start() {
        return tick();
    }

    TaskModuleResult tick() {
        if (!source.dimensionMatches()) {
            return TaskModuleResult.failed("拾取区域维度不匹配");
        }
        recordInventoryDelta();
        List<DroppedItem> drops = matches();
        if (current != null) {
            return continueCurrent(drops);
        }
        if (drops.isEmpty()) {
            emptyTicks++;
            if (emptyTicks >= EMPTY_CONFIRM_TICKS) {
                return TaskModuleResult.succeeded("掉落物已拾取完毕");
            }
            return TaskModuleResult.running("确认拾取区域为空");
        }
        current = drops.getFirst();
        navigationStarted = false;
        emptyTicks = 0;
        return tick();
    }

    void cancel() {
        context.navigation().cancel();
    }

    private TaskModuleResult continueCurrent(List<DroppedItem> drops) {
        if (timedOut()) {
            return TaskModuleResult.failed("拾取掉落物超时");
        }
        DroppedItem observed = drops.stream()
                .filter(item -> item.entityId().equals(current.entityId()))
                .findFirst()
                .orElse(null);
        if (observed == null) {
            if (!inventoryTracking) {
                context.workspace().recordCollected(
                        current.itemId(),
                        current.count()
                );
            }
            current = null;
            navigationStarted = false;
            emptyTicks = 0;
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
            case ARRIVED -> TaskModuleResult.running(
                    "等待拾取 " + observed.itemId()
            );
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

    private void recordInventoryDelta() {
        Optional<Map<String, Integer>> observed =
                context.droppedItems().playerItemCounts();
        if (observed.isEmpty()) {
            return;
        }
        Map<String, Integer> currentInventory = observed.orElseThrow();
        if (!inventoryTracking) {
            previousInventory = observed;
            inventoryTracking = true;
            return;
        }
        Map<String, Integer> previous = previousInventory.orElseThrow();
        for (Map.Entry<String, Integer> entry : currentInventory.entrySet()) {
            int added = entry.getValue()
                    - previous.getOrDefault(entry.getKey(), 0);
            if (added > 0 && TaskModuleSupport.itemMatches(
                    entry.getKey(),
                    itemFilter
            )) {
                context.workspace().recordCollected(entry.getKey(), added);
            }
        }
        previousInventory = observed;
    }

    private boolean timedOut() {
        return timeoutSeconds > 0
                && Duration.between(startedAt, Instant.now()).toSeconds()
                >= timeoutSeconds;
    }
}
