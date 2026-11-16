package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Set;

final class CollectDroppedItemsModule implements TaskModuleExecutor {
    private final DroppedItemCollectionEngine engine;

    CollectDroppedItemsModule(TaskExecutionContext context, TaskStep step) {
        int timeoutSeconds = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.TIMEOUT_SECONDS,
                TaskModuleSupport.DEFAULT_COLLECT_TIMEOUT_SECONDS
        );
        Set<String> itemFilter = TaskModuleSupport.itemFilter(step);
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.AREA_REF,
                ResourceType.COORDINATE,
                TaskModuleIds.AREA_REFERENCE,
                TaskModuleIds.COORDINATE_ID
        );
        this.engine = reference.type() == ResourceType.REGION
                ? regionEngine(
                        context,
                        ResourceReferenceSupport.region(context, reference),
                        timeoutSeconds,
                        itemFilter
                )
                : coordinateEngine(
                        context,
                        ResourceReferenceSupport.coordinate(context, reference),
                        timeoutSeconds,
                        itemFilter
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
    public void requestStop() {
        engine.requestStop();
    }

    @Override
    public boolean safeToStop() {
        return engine.safeToStop();
    }

    @Override
    public void cancel() {
        engine.cancel();
    }

    private static DroppedItemCollectionEngine coordinateEngine(
            TaskExecutionContext context,
            CoordinatePoint coordinate,
            int timeoutSeconds,
            Set<String> itemFilter
    ) {
        BlockPosition center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        int radius = TaskModuleSupport.dropRadius(coordinate);
        return new DroppedItemCollectionEngine(
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
                timeoutSeconds,
                itemFilter
        );
    }

    private static DroppedItemCollectionEngine regionEngine(
            TaskExecutionContext context,
            RegionDefinition region,
            int timeoutSeconds,
            Set<String> itemFilter
    ) {
        return new DroppedItemCollectionEngine(
                context,
                new DroppedItemCollectionEngine.Source() {
                    @Override
                    public boolean dimensionMatches() {
                        return true;
                    }

                    @Override
                    public List<DroppedItem> observe() {
                        return context.droppedItems().observe(region);
                    }
                },
                timeoutSeconds,
                itemFilter
        );
    }
}
