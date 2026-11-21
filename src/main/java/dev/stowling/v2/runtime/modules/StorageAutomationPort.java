package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.DroppedItem;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared storage and cleanup actions; independent of the task that invokes them. */
public interface StorageAutomationPort {
    StorageAutomationPort UNAVAILABLE = new StorageAutomationPort() {
        @Override
        public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                Map<String, Integer> items) {
            throw new IllegalStateException("当前环境不支持潜影盒打包");
        }

        @Override
        public TaskModuleExecutor cleanup(TaskExecutionContext context) {
            throw new IllegalStateException("当前环境不支持全局收尾");
        }
    };

    TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
            Map<String, Integer> items);

    TaskModuleExecutor cleanup(TaskExecutionContext context);

    default TaskModuleExecutor collectMaterials(TaskExecutionContext context,
            dev.stowling.v2.model.TaskStep step) {
        throw new IllegalStateException("当前环境不支持从仓库收集投影材料");
    }

    default TaskModuleExecutor cleanup(TaskExecutionContext context, boolean overflowEnabled) {
        return cleanup(context);
    }

    default TaskModuleExecutor overflow(TaskExecutionContext context,
            RegionDefinition cacheRegion) {
        throw new IllegalStateException("当前环境不支持溢出打包");
    }

    default TaskModuleExecutor robotWarehouse(TaskExecutionContext context,
            RegionDefinition cacheRegion) {
        throw new IllegalStateException("当前环境不支持机器人仓库");
    }

    default TaskModuleExecutor robotWarehouse(TaskExecutionContext context,
            RegionDefinition cacheRegion, ResourceReference destination) {
        if (destination != null) throw new IllegalStateException("当前环境不支持指定仓库位置");
        return robotWarehouse(context, cacheRegion);
    }

    default TaskModuleExecutor scanCacheContents(TaskExecutionContext context,
            List<ContainerTarget> containers) {
        throw new IllegalStateException("当前环境不支持缓存库存盘点");
    }

    default Set<String> unstackableItems(TaskExecutionContext context, ContainerTarget cache) {
        throw new IllegalStateException("当前环境不支持读取物品堆叠上限");
    }

    default Comparator<ContainerTarget> storageOrder(Map<String, Integer> items) {
        return (first, second) -> 0;
    }

    default java.util.Optional<TaskModuleExecutor> prepareDeposit(
            TaskExecutionContext context, ContainerTarget target, Map<String, Integer> items) {
        return java.util.Optional.empty();
    }

    default java.util.Optional<TaskModuleExecutor> unpackMixedBox(
            TaskExecutionContext context, ContainerTarget cache) {
        return java.util.Optional.empty();
    }

    default boolean cleanupEnabled() {
        return false;
    }

    default java.util.List<ContainerTarget> temporaryContainers() {
        return java.util.List.of();
    }

    default java.util.List<ContainerTarget> recoveryContainers(TaskExecutionContext context) {
        return temporaryContainers();
    }

    default List<ContainerTarget> partialBoxSources(TaskExecutionContext context,
            List<ContainerTarget> targets) {
        return List.of();
    }

    default boolean hasTemporaryResources(UUID owner) {
        return false;
    }

    /** Releases tracking after the user takes responsibility for the temporary resources. */
    default void releaseTemporaryResources(UUID owner) {
    }

    default boolean isRecoveryDrop(DroppedItem item) {
        return false;
    }
}
