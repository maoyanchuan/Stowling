package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class WaitForDroppedItemsModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CoordinatePoint coordinate;
    private final BlockPosition center;
    private final int radius;
    private final int timeoutSeconds;
    private final Set<String> itemFilter;
    private final Instant startedAt = Instant.now();

    WaitForDroppedItemsModule(TaskExecutionContext context, TaskStep step) {
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
                0
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
            return TaskModuleResult.failed("监测坐标维度不匹配");
        }
        List<DroppedItem> matches = context.droppedItems()
                .observe(center, radius)
                .stream()
                .filter(item -> TaskModuleSupport.itemMatches(
                        item.itemId(),
                        itemFilter
                ))
                .toList();
        if (!matches.isEmpty()) {
            return TaskModuleResult.succeeded(
                    "检测到 " + matches.size() + " 组掉落物"
            );
        }
        if (timedOut()) {
            return TaskModuleResult.failed("等待掉落物超时");
        }
        return TaskModuleResult.waiting(
                "等待 " + coordinate.name() + " 出现掉落物"
        );
    }

    @Override
    public void cancel() {
    }

    private boolean timedOut() {
        return timeoutSeconds > 0
                && Duration.between(startedAt, Instant.now()).toSeconds()
                >= timeoutSeconds;
    }
}
