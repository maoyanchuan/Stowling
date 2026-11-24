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
                factory(dev.stowling.v2.model.EmptyBoxWarehousePolicy.SETTLE_MODULE,
                        (context, step) -> context.storageAutomation().settleEmptyBoxes(context)),
                factory(dev.stowling.v2.model.ContainerCacheRefresh.MODULE,
                        (context, step) -> context.storageAutomation()
                                .refreshContainerCache(context, step)),
                factory("MANAGE_CACHE_INTAKE", ManageCacheIntakeModule::new),
                factory(dev.stowling.v2.model.MaterialRequirements.MODULE,
                        (context, step) -> context.storageAutomation()
                                .collectMaterials(context, step)),
                factory(ConfigValidator.PACK_OVERFLOW, OverflowItemsModule::create),
                factory(ConfigValidator.ROBOT_WAREHOUSE, OverflowItemsModule::create),
                factory(dev.stowling.v2.model.ClassificationRemainderPolicy.MODULE,
                        OverflowItemsModule::create),
                factory("GLOBAL_INVENTORY_CLEANUP",
                        (context, step) -> context.storageAutomation().cleanup(context,
                                TaskModuleSupport.booleanParameter(step, "overflowEnabled", false))),
                factory(
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        MoveToCoordinateModule::new
                ),
                factory(
                        TaskModuleIds.COLLECT_DROPPED_ITEMS,
                        CollectDroppedItemsModule::new
                ),
                factory(
                        TaskModuleIds.SCAN_CONTAINERS_IN_REGION,
                        ScanContainersInRegionModule::new
                ),
                factory(
                        TaskModuleIds.RESOLVE_CONTAINER_CATEGORIES,
                        ResolveContainerCategoriesModule::new
                ),
                factory(ConfigValidator.RESOLVE_UNSTACKABLE_STORAGE,
                        ResolveUnstackableStorageModule::new),
                factory(
                        TaskModuleIds.SELECT_CONTAINER,
                        SelectContainerModule::new
                ),
                factory(
                        TaskModuleIds.WITHDRAW_ITEMS,
                        WithdrawItemsModule::new
                ),
                factory(
                        TaskModuleIds.SELECT_STORAGE_TARGET,
                        SelectStorageTargetModule::new
                ),
                factory(
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        DepositItemsActionModule::new
                ),
                factory(
                        TaskModuleIds.WITHDRAW_FOOD,
                        WithdrawFoodModule::new
                ),
                factory(
                        TaskModuleIds.EAT_FOOD,
                        EatFoodModule::new
                ),
                factory(
                        TaskModuleIds.SCAN_CATEGORY_MARKERS,
                        ScanCategoryMarkersModule::new
                ),
                factory(
                        TaskModuleIds.SELECT_MARKER_CONTAINER,
                        SelectMarkerContainerModule::new
                ),
                factory(
                        TaskModuleIds.READ_CONTAINER,
                        ReadContainerModule::new
                ),
                factory(
                        TaskModuleIds.SAVE_CATEGORY_MARKER_CACHE,
                        SaveCategoryMarkerCacheModule::new
                ),
                factory(
                        TaskModuleIds.ASSERT_NO_MANAGED_ITEMS,
                        AssertNoManagedItemsModule::new
                ),
                factory(
                        TaskModuleIds.ASSERT_PLAYER_INVENTORY_RESTORED,
                        AssertPlayerInventoryRestoredModule::new
                ),
                factory(
                        TaskModuleIds.SYNC_PLAYER_INVENTORY,
                        SyncPlayerInventoryModule::new
                ),
                factory(
                        TaskModuleIds.SYNC_COLLECTED_INVENTORY,
                        SyncPlayerInventoryModule::new
                ),
                factory(
                        TaskModuleIds.WAIT_FOR_DROPPED_ITEMS,
                        WaitForDroppedItemsModule::new
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
