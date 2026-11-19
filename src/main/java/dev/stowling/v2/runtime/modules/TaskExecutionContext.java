package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.runtime.TaskTracePorts;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TaskExecutionContext(
        NavigationPort navigation,
        DroppedItemObserverPort droppedItems,
        ContainerRegionPort containerRegions,
        ContainerTransferPort containerTransfers,
        ContainerWithdrawalPort containerWithdrawals,
        ContainerInspectionPort containerInspections,
        CategoryMarkerCachePort categoryMarkerCache,
        FoodConsumptionPort foodConsumption,
        CategoryZonePort categoryZonesPort,
        Map<UUID, CoordinatePoint> coordinates,
        Map<UUID, RegionDefinition> regions,
        Map<UUID, CategoryZoneDefinition> categoryZones,
        TaskRunWorkspace workspace,
        PlayerInventoryManager inventory,
        StorageAutomationPort storageAutomation
) {
    public TaskExecutionContext {
        navigation = Objects.requireNonNull(navigation, "navigation");
        droppedItems = Objects.requireNonNull(droppedItems, "droppedItems");
        containerRegions = Objects.requireNonNull(
                containerRegions,
                "containerRegions"
        );
        containerTransfers = Objects.requireNonNull(
                containerTransfers,
                "containerTransfers"
        );
        containerWithdrawals = Objects.requireNonNull(
                containerWithdrawals,
                "containerWithdrawals"
        );
        containerInspections = Objects.requireNonNull(
                containerInspections,
                "containerInspections"
        );
        categoryMarkerCache = Objects.requireNonNull(
                categoryMarkerCache,
                "categoryMarkerCache"
        );
        foodConsumption = Objects.requireNonNull(
                foodConsumption,
                "foodConsumption"
        );
        categoryZonesPort = Objects.requireNonNull(
                categoryZonesPort,
                "categoryZonesPort"
        );
        coordinates = Map.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
        regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
        categoryZones = Map.copyOf(
                Objects.requireNonNull(categoryZones, "categoryZones")
        );
        workspace = Objects.requireNonNull(workspace, "workspace");
        inventory = Objects.requireNonNull(inventory, "inventory");
        storageAutomation = Objects.requireNonNull(storageAutomation);
        inventory.register(workspace.inventoryAccount());
        workspace.initialInventory().ifPresent(inventory::initialize);
        if (workspace.diagnostics() != null) {
            navigation = TaskTracePorts.wrap(NavigationPort.class, navigation);
            droppedItems = TaskTracePorts.wrap(
                    DroppedItemObserverPort.class, droppedItems);
            containerRegions = TaskTracePorts.wrap(
                    ContainerRegionPort.class, containerRegions);
            containerTransfers = TaskTracePorts.wrap(
                    ContainerTransferPort.class, containerTransfers);
            containerWithdrawals = TaskTracePorts.wrap(
                    ContainerWithdrawalPort.class, containerWithdrawals);
            containerInspections = TaskTracePorts.wrap(
                    ContainerInspectionPort.class, containerInspections);
            categoryMarkerCache = TaskTracePorts.wrap(
                    CategoryMarkerCachePort.class, categoryMarkerCache);
            foodConsumption = TaskTracePorts.wrap(
                    FoodConsumptionPort.class, foodConsumption);
            categoryZonesPort = TaskTracePorts.wrap(
                    CategoryZonePort.class, categoryZonesPort);
            storageAutomation = TaskTracePorts.wrap(
                    StorageAutomationPort.class, storageAutomation);
        }
    }

    public TaskExecutionContext(
            NavigationPort navigation, DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions, ContainerTransferPort containerTransfers,
            ContainerWithdrawalPort containerWithdrawals,
            ContainerInspectionPort containerInspections,
            CategoryMarkerCachePort categoryMarkerCache, FoodConsumptionPort foodConsumption,
            CategoryZonePort categoryZonesPort, Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions, Map<UUID, CategoryZoneDefinition> categoryZones,
            TaskRunWorkspace workspace, PlayerInventoryManager inventory
    ) {
        this(navigation, droppedItems, containerRegions, containerTransfers, containerWithdrawals,
                containerInspections, categoryMarkerCache, foodConsumption, categoryZonesPort,
                coordinates, regions, categoryZones, workspace, inventory,
                StorageAutomationPort.UNAVAILABLE);
    }

    public TaskExecutionContext(
            NavigationPort navigation,
            DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions,
            ContainerTransferPort containerTransfers,
            ContainerWithdrawalPort containerWithdrawals,
            ContainerInspectionPort containerInspections,
            CategoryMarkerCachePort categoryMarkerCache,
            FoodConsumptionPort foodConsumption,
            CategoryZonePort categoryZonesPort,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            TaskRunWorkspace workspace
    ) {
        this(navigation, droppedItems, containerRegions, containerTransfers,
                containerWithdrawals, containerInspections, categoryMarkerCache,
                foodConsumption, categoryZonesPort, coordinates, regions, categoryZones,
                workspace, new PlayerInventoryManager(() -> droppedItems.playerItemCounts()
                        .map(dev.stowling.automation.PlayerInventorySnapshot::new)));
    }

    public TaskExecutionContext(
            NavigationPort navigation,
            DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions,
            ContainerTransferPort containerTransfers,
            ContainerWithdrawalPort containerWithdrawals,
            CategoryZonePort categoryZonesPort,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            TaskRunWorkspace workspace
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
                categoryZonesPort,
                coordinates,
                regions,
                categoryZones,
                workspace
        );
    }

    public TaskExecutionContext(
            NavigationPort navigation,
            DroppedItemObserverPort droppedItems,
            ContainerRegionPort containerRegions,
            ContainerTransferPort containerTransfers,
            ContainerWithdrawalPort containerWithdrawals,
            ContainerInspectionPort containerInspections,
            CategoryMarkerCachePort categoryMarkerCache,
            CategoryZonePort categoryZonesPort,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            TaskRunWorkspace workspace
    ) {
        this(
                navigation,
                droppedItems,
                containerRegions,
                containerTransfers,
                containerWithdrawals,
                containerInspections,
                categoryMarkerCache,
                FoodConsumptionPort.UNAVAILABLE,
                categoryZonesPort,
                coordinates,
                regions,
                categoryZones,
                workspace
        );
    }
}
