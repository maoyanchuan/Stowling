package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import java.util.Map;
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

    default boolean cleanupEnabled() {
        return false;
    }

    default java.util.List<ContainerTarget> temporaryContainers() {
        return java.util.List.of();
    }

    default boolean hasTemporaryResources(UUID owner) {
        return false;
    }
}
