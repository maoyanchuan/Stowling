package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.v2.model.TaskStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TaskModuleRuntimeRegistry {
    private final NavigationPort navigation;
    private final DroppedItemObserverPort droppedItems;
    private final ContainerRegionPort containerRegions;
    private final ContainerTransferPort containerTransfers;
    private final Map<String, TaskModuleRuntimeFactory> factories;

    public TaskModuleRuntimeRegistry(
            NavigationPort navigation,
            DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions,
            ContainerTransferPort containerTransfers,
            List<TaskModuleRuntimeFactory> factories
    ) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.droppedItems = Objects.requireNonNull(droppedItems, "droppedItems");
        this.containerRegions = Objects.requireNonNull(
                containerRegions,
                "containerRegions"
        );
        this.containerTransfers = Objects.requireNonNull(
                containerTransfers,
                "containerTransfers"
        );
        Map<String, TaskModuleRuntimeFactory> byId = new LinkedHashMap<>();
        for (TaskModuleRuntimeFactory factory : factories) {
            TaskModuleRuntimeFactory previous = byId.put(
                    factory.moduleId(),
                    factory
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate task module factory: " + factory.moduleId()
                );
            }
        }
        this.factories = Map.copyOf(byId);
    }

    public static TaskModuleRuntimeRegistry unavailablePorts(
            NavigationPort navigation
    ) {
        DroppedItemObserverPort droppedItems = (center, radius) -> {
            throw new IllegalStateException("掉落物观察组件不可用");
        };
        ContainerRegionPort containerRegions = (region, types, maxVolume) ->
                ContainerScanResult.failure("区域扫描组件不可用");
        ContainerTransferPort transfers = new ContainerTransferPort() {
            @Override
            public ContainerTransferResult startDeposit(
                    ContainerTarget target,
                    Map<String, Integer> items
            ) {
                return ContainerTransferResult.failed(
                        "容器存入组件不可用",
                        items
                );
            }

            @Override
            public ContainerTransferResult pollDeposit() {
                return ContainerTransferResult.failed(
                        "容器存入组件不可用",
                        Map.of()
                );
            }

            @Override
            public void cancelDeposit() {
            }
        };
        return new TaskModuleRuntimeRegistry(
                navigation,
                droppedItems,
                containerRegions,
                transfers,
                StandardTaskModuleFactories.create()
        );
    }

    public NavigationPort navigation() {
        return navigation;
    }

    public DroppedItemObserverPort droppedItems() {
        return droppedItems;
    }

    public ContainerRegionPort containerRegions() {
        return containerRegions;
    }

    public ContainerTransferPort containerTransfers() {
        return containerTransfers;
    }

    public TaskModuleExecutor create(
            TaskStep step,
            TaskExecutionContext context
    ) {
        TaskModuleRuntimeFactory factory = factories.get(step.moduleId());
        if (factory == null) {
            throw new IllegalArgumentException(
                    "task module runtime is unavailable: " + step.moduleId()
            );
        }
        return factory.create(step, context);
    }
}
