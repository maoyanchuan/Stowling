package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategoryPort;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record TaskExecutionContext(
        NavigationPort navigation,
        DroppedItemObserverPort droppedItems,
        ContainerRegionPort containerRegions,
        ContainerCategoryPort containerCategories,
        ContainerTransferPort containerTransfers,
        ContainerWithdrawalPort containerWithdrawals,
        Map<UUID, CoordinatePoint> coordinates,
        Map<UUID, RegionDefinition> regions,
        TaskRunWorkspace workspace
) {
    public TaskExecutionContext {
        navigation = Objects.requireNonNull(navigation, "navigation");
        droppedItems = Objects.requireNonNull(droppedItems, "droppedItems");
        containerRegions = Objects.requireNonNull(
                containerRegions,
                "containerRegions"
        );
        containerCategories = Objects.requireNonNull(
                containerCategories,
                "containerCategories"
        );
        containerTransfers = Objects.requireNonNull(
                containerTransfers,
                "containerTransfers"
        );
        containerWithdrawals = Objects.requireNonNull(
                containerWithdrawals,
                "containerWithdrawals"
        );
        coordinates = Map.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
        regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
        workspace = Objects.requireNonNull(workspace, "workspace");
    }
}
