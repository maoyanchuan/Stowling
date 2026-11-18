package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TaskModuleRuntimeRegistry {
    private StorageAutomationPort storageAutomation = StorageAutomationPort.UNAVAILABLE;
    private final NavigationPort navigation;
    private final DroppedItemObserverPort droppedItems;
    private final PlayerInventoryManager inventory;
    private final ContainerRegionPort containerRegions;
    private final ContainerTransferPort containerTransfers;
    private final ContainerWithdrawalPort containerWithdrawals;
    private final ContainerInspectionPort containerInspections;
    private final CategoryMarkerCachePort categoryMarkerCache;
    private final FoodConsumptionPort foodConsumption;
    private final CategoryZonePort categoryZones;
    private final Map<String, TaskModuleRuntimeFactory> factories;

    public TaskModuleRuntimeRegistry(
            NavigationPort navigation,
            DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions,
            ContainerTransferPort containerTransfers,
            ContainerWithdrawalPort containerWithdrawals,
            ContainerInspectionPort containerInspections,
            CategoryMarkerCachePort categoryMarkerCache,
            FoodConsumptionPort foodConsumption,
            CategoryZonePort categoryZones,
            List<TaskModuleRuntimeFactory> factories
    ) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.droppedItems = Objects.requireNonNull(droppedItems, "droppedItems");
        this.inventory = new PlayerInventoryManager(() -> this.droppedItems.playerItemCounts()
                .map(dev.stowling.automation.PlayerInventorySnapshot::new));
        this.containerRegions = Objects.requireNonNull(
                containerRegions,
                "containerRegions"
        );
        this.containerTransfers = Objects.requireNonNull(
                containerTransfers,
                "containerTransfers"
        );
        this.containerWithdrawals = Objects.requireNonNull(
                containerWithdrawals,
                "containerWithdrawals"
        );
        this.containerInspections = Objects.requireNonNull(
                containerInspections,
                "containerInspections"
        );
        this.categoryMarkerCache = Objects.requireNonNull(
                categoryMarkerCache,
                "categoryMarkerCache"
        );
        this.foodConsumption = Objects.requireNonNull(
                foodConsumption,
                "foodConsumption"
        );
        this.categoryZones = Objects.requireNonNull(
                categoryZones,
                "categoryZones"
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

    public TaskModuleRuntimeRegistry(
            NavigationPort navigation,
            DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions,
            ContainerTransferPort containerTransfers,
            ContainerWithdrawalPort containerWithdrawals,
            CategoryZonePort categoryZones,
            List<TaskModuleRuntimeFactory> factories
    ) {
        this(
                navigation,
                droppedItems,
                containerRegions,
                containerTransfers,
                containerWithdrawals,
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                FoodConsumptionPort.UNAVAILABLE,
                categoryZones,
                factories
        );
    }

    public static TaskModuleRuntimeRegistry unavailablePorts(
            NavigationPort navigation
    ) {
        DroppedItemObserverPort droppedItems = new DroppedItemObserverPort() {
            @Override
            public List<DroppedItem> observe(
                    BlockPosition center,
                    int radius
            ) {
                throw new IllegalStateException("掉落物观察组件不可用");
            }

            @Override
            public List<DroppedItem> observe(
                    RegionDefinition region
            ) {
                throw new IllegalStateException("掉落物观察组件不可用");
            }
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
        ContainerWithdrawalPort withdrawals = new ContainerWithdrawalPort() {
            @Override
            public ContainerWithdrawalResult startWithdrawal(
                    ContainerTarget target,
                    Set<String> matchingItemIds
            ) {
                return ContainerWithdrawalResult.failed(
                        "缓存取出组件不可用",
                        Map.of(),
                        false
                );
            }

            @Override
            public ContainerWithdrawalResult pollWithdrawal() {
                return ContainerWithdrawalResult.failed(
                        "缓存取出组件不可用",
                        Map.of(),
                        false
                );
            }

            @Override
            public void cancelWithdrawal() {
            }
        };
        CategoryZonePort categoryZones = (zone, types, maxVolume) ->
                CategoryZoneResult.failure("分类区解析组件不可用");
        return new TaskModuleRuntimeRegistry(
                navigation,
                droppedItems,
                containerRegions,
                transfers,
                withdrawals,
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                FoodConsumptionPort.UNAVAILABLE,
                categoryZones,
                StandardTaskModuleFactories.create()
        );
    }

    public void setStorageAutomation(StorageAutomationPort storageAutomation) {
        this.storageAutomation = Objects.requireNonNull(storageAutomation);
    }

    public StorageAutomationPort storageAutomation() {
        return storageAutomation;
    }

    public NavigationPort navigation() {
        return navigation;
    }

    public DroppedItemObserverPort droppedItems() {
        return droppedItems;
    }

    public PlayerInventoryManager inventory() {
        return inventory;
    }

    public ContainerRegionPort containerRegions() {
        return containerRegions;
    }

    public ContainerTransferPort containerTransfers() {
        return containerTransfers;
    }

    public ContainerWithdrawalPort containerWithdrawals() {
        return containerWithdrawals;
    }

    public ContainerInspectionPort containerInspections() {
        return containerInspections;
    }

    public CategoryMarkerCachePort categoryMarkerCache() {
        return categoryMarkerCache;
    }

    public FoodConsumptionPort foodConsumption() {
        return foodConsumption;
    }

    public CategoryZonePort categoryZones() {
        return categoryZones;
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
