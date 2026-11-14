package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.StorageRoute;
import java.util.List;
import java.util.Objects;

public record TaskPackingPlan(
        StorageRoute route,
        List<PackingStation> stations,
        ContainerRef overflowContainer
) {
    public TaskPackingPlan {
        route = Objects.requireNonNull(route, "route");
        stations = List.copyOf(Objects.requireNonNull(stations, "stations"));
        overflowContainer = Objects.requireNonNull(
                overflowContainer,
                "overflowContainer"
        );
        if (!route.packingEnabled()) {
            throw new IllegalArgumentException("task packing route must be enabled");
        }
        if (stations.isEmpty()) {
            throw new IllegalArgumentException("task packing stations must not be empty");
        }
        String dimension = route.bulkContainer().dimension();
        if (!overflowContainer.dimension().equals(dimension)) {
            throw new IllegalArgumentException(
                    "task overflow container must share the route dimension"
            );
        }
        boolean stationDimensionMismatch = stations.stream()
                .anyMatch(station -> !station.dimension().equals(dimension));
        if (stationDimensionMismatch) {
            throw new IllegalArgumentException(
                    "task packing stations must share the route dimension"
            );
        }
    }
}
