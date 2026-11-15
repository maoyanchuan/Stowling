package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.DroppedItem;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.List;

final class CollectDroppedItemsInRegionModule
        implements TaskModuleExecutor {
    private final DroppedItemCollectionEngine engine;

    CollectDroppedItemsInRegionModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        RegionDefinition region = TaskModuleSupport.region(context, step);
        this.engine = new DroppedItemCollectionEngine(
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
