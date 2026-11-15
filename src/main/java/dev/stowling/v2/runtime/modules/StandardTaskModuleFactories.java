package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.function.BiFunction;

public final class StandardTaskModuleFactories {
    private StandardTaskModuleFactories() {
    }

    public static List<TaskModuleRuntimeFactory> create() {
        return List.of(
                factory(
                        ConfigValidator.MOVE_TO_COORDINATE,
                        MoveToCoordinateModule::new
                ),
                factory(
                        ConfigValidator.WAIT_FOR_DROPPED_ITEMS,
                        WaitForDroppedItemsModule::new
                ),
                factory(
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        CollectDroppedItemsModule::new
                ),
                factory(
                        ConfigValidator.COLLECT_DROPPED_ITEMS_IN_REGION,
                        CollectDroppedItemsInRegionModule::new
                ),
                factory(
                        ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                        ScanContainersInRegionModule::new
                ),
                factory(
                        ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                        MatchContainerCategoriesModule::new
                ),
                factory(
                        ConfigValidator.SORT_CACHE_ITEMS,
                        SortCacheItemsModule::new
                ),
                factory(
                        ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                        DepositItemsToContainersModule::new
                )
        );
    }

    private static TaskModuleRuntimeFactory factory(
            String moduleId,
            BiFunction<TaskExecutionContext, TaskStep, TaskModuleExecutor> creator
    ) {
        return new Factory(moduleId, creator);
    }

    private record Factory(
            String moduleId,
            BiFunction<TaskExecutionContext, TaskStep, TaskModuleExecutor> creator
    ) implements TaskModuleRuntimeFactory {
        @Override
        public TaskModuleExecutor create(
                TaskStep step,
                TaskExecutionContext context
        ) {
            return creator.apply(context, step);
        }
    }
}
