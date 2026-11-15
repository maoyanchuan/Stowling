package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class CollectDroppedItemsModule implements TaskModuleExecutor {
    private static final int EMPTY_CONFIRM_TICKS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final TaskExecutionContext context;
    private final CoordinatePoint coordinate;
    private final BlockPosition center;
    private final int radius;
    private final int timeoutSeconds;
    private final Set<String> itemFilter;
    private final Instant startedAt = Instant.now();
    private DroppedItem current;
    private boolean navigationStarted;
    private int emptyTicks;

    CollectDroppedItemsModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.coordinate = TaskModuleSupport.coordinate(context, step);
        this.center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        this.radius = TaskModuleSupport.dropRadius(coordinate);
        this.timeoutSeconds = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.TIMEOUT_SECONDS,
                DEFAULT_TIMEOUT_SECONDS
        );
        this.itemFilter = TaskModuleSupport.itemFilter(step);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (!TaskModuleSupport.dimensionMatches(context, coordinate)) {
            return TaskModuleResult.failed("拾取坐标维度不匹配");
        }
        List<DroppedItem> drops = matches();
        if (current != null) {
            return continueCurrent(drops);
        }
        if (drops.isEmpty()) {
            emptyTicks++;
            if (emptyTicks >= EMPTY_CONFIRM_TICKS) {
                return TaskModuleResult.succeeded("附近掉落物已拾取完毕");
            }
            return TaskModuleResult.running("确认拾取区域为空");
        }
        current = drops.getFirst();
        navigationStarted = false;
        emptyTicks = 0;
        return tick();
    }

    @Override
    public void cancel() {
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
            context.workspace().recordCollected(current.itemId(), current.count());
            current = null;
            navigationStarted = false;
            emptyTicks = 0;
            return TaskModuleResult.running("已拾取一组掉落物");
        }
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
        return context.droppedItems().observe(center, radius).stream()
                .filter(item -> TaskModuleSupport.itemMatches(
                        item.itemId(),
                        itemFilter
                ))
                .toList();
    }

    private boolean timedOut() {
        return timeoutSeconds > 0
                && Duration.between(startedAt, Instant.now()).toSeconds()
                >= timeoutSeconds;
    }
}
