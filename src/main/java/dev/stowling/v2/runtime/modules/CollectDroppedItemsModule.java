package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;
import java.util.List;

final class CollectDroppedItemsModule implements TaskModuleExecutor {
    private final CoordinatePoint coordinate;
    private final BlockPosition center;
    private final int radius;
    private final DroppedItemCollectionEngine engine;

    CollectDroppedItemsModule(TaskExecutionContext context, TaskStep step) {
        this.coordinate = TaskModuleSupport.coordinate(context, step);
        this.center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        this.radius = TaskModuleSupport.dropRadius(coordinate);
        this.engine = new DroppedItemCollectionEngine(
                context,
                new DroppedItemCollectionEngine.Source() {
                    @Override
                    public boolean dimensionMatches() {
                        return TaskModuleSupport.dimensionMatches(
                                context,
                                coordinate
                        );
                    }

                    @Override
                    public List<DroppedItem> observe() {
                        return context.droppedItems().observe(center, radius);
                    }
                },
                TaskModuleSupport.integerParameter(
                        step,
                        ConfigValidator.TIMEOUT_SECONDS,
                        TaskModuleSupport.DEFAULT_COLLECT_TIMEOUT_SECONDS
                ),
                TaskModuleSupport.itemFilter(step)
        );
    }

    @Override
    public TaskModuleResult start() {
        return engine.start();
    }

    @Override
    public TaskModuleResult tick() {
        return engine.tick();
    }

    @Override
    public void cancel() {
        engine.cancel();
    }
}
