package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerTransferPort;
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
        ContainerTransferPort containerTransfers,
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
        containerTransfers = Objects.requireNonNull(
                containerTransfers,
                "containerTransfers"
        );
        coordinates = Map.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
        regions = Map.copyOf(Objects.requireNonNull(regions, "regions"));
        workspace = Objects.requireNonNull(workspace, "workspace");
    }
}
