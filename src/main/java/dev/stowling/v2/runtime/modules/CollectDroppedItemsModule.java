package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Set;

final class CollectDroppedItemsModule implements TaskModuleExecutor {
    private final DroppedItemCollectionEngine engine;

    CollectDroppedItemsModule(TaskExecutionContext context, TaskStep step) {
        this.engine = createEngine(context, step, false);
    }

    static DroppedItemCollectionEngine createEngine(TaskExecutionContext context, TaskStep step,
            boolean cacheCycle) {
        int timeoutSeconds = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.TIMEOUT_SECONDS,
                TaskModuleSupport.DEFAULT_COLLECT_TIMEOUT_SECONDS
        );
        Set<String> itemFilter = TaskModuleSupport.itemFilter(step);
        int pickupRadius = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.PICKUP_RADIUS,
                -1
        );
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.AREA_REF,
                ResourceType.COORDINATE,
                TaskModuleIds.AREA_REFERENCE,
                TaskModuleIds.COORDINATE_ID
        );
        return reference.type() == ResourceType.REGION
                ? regionEngine(
                        context,
                        ResourceReferenceSupport.region(context, reference),
                        timeoutSeconds,
                        itemFilter,
                        pickupRadius,
                        cacheCycle
                )
                : coordinateEngine(
                        context,
                        ResourceReferenceSupport.coordinate(context, reference),
                        timeoutSeconds,
                        itemFilter,
                        pickupRadius,
                        TaskModuleSupport.integerParameter(step,
                                ConfigValidator.DEFAULT_PICKUP_RADIUS,
                                TaskModuleSupport.DEFAULT_DROP_RADIUS),
                        cacheCycle
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
            Set<String> itemFilter,
            int pickupRadius,
            int defaultRadius,
            boolean cacheCycle
    ) {
        BlockPosition center = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
        int radius = pickupRadius > 0
                ? pickupRadius
                : coordinate.radius() == null ? defaultRadius : coordinate.radius();
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

                    @Override
                    public boolean canPickUp(DroppedItem item) {
                        return context.droppedItems().canPickUp(item);
                    }
                },
                timeoutSeconds,
                itemFilter,
                cacheCycle
        );
    }

    private static DroppedItemCollectionEngine regionEngine(
            TaskExecutionContext context,
            RegionDefinition region,
            int timeoutSeconds,
            Set<String> itemFilter,
            int pickupRadius,
            boolean cacheCycle
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

                    @Override
                    public boolean canPickUp(DroppedItem item) {
                        return context.droppedItems().canPickUp(item);
                    }
                },
                timeoutSeconds,
                itemFilter,
                cacheCycle
        );
    }
}
