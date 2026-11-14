package dev.stowling.control;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.SpherePickupShape;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only remove resource references explicitly confirmed for deleted named coordinates. */
final class CoordinateReferenceCleanup {
    private CoordinateReferenceCleanup() {
    }

    static StowlingConfig apply(
            StowlingConfig source,
            Map<String, NamedCoordinate> remaining,
            List<String> confirmedIds
    ) {
        if (confirmedIds.isEmpty()) {
            return source;
        }
        Set<String> removed = new LinkedHashSet<>(confirmedIds);
        Set<String> savedIds = new LinkedHashSet<>();
        for (NamedCoordinate coordinate : source.coordinatePoints()) {
            savedIds.add(coordinate.id());
        }
        for (String id : removed) {
            if (!savedIds.contains(id) || remaining.containsKey(id)) {
                throw new InvalidCoordinatePointsException(
                        "Cleanup requires a saved coordinate deleted in this request: " + id
                );
            }
        }
        Set<String> regionIds = new LinkedHashSet<>();
        for (PickupRegion region : source.pickupRegions()) {
            if (referencesRemovedCoordinate(region, removed)) {
                regionIds.add(region.id());
            }
        }
        Set<String> cacheIds = new LinkedHashSet<>();
        source.cacheContainers().stream()
                .filter(cache -> removed.contains(cache.coordinateId()))
                .forEach(cache -> cacheIds.add(cache.id()));
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                source.coordinatePoints(),
                source.pickupRegions().stream()
                        .filter(region -> !regionIds.contains(region.id())).toList(),
                source.cacheContainers().stream()
                        .filter(cache -> !cacheIds.contains(cache.id())).toList(),
                source.pickupWorkflows().stream()
                        .filter(workflow -> workflow.regions().stream()
                                .noneMatch(binding -> regionIds.contains(binding.regionId())))
                        .filter(workflow -> workflow.cacheIds().stream()
                                .noneMatch(cacheIds::contains)).toList(),
                source.automationWorkflows(),
                source.storageRoutes(),
                source.packingStations(),
                source.auditSchedules().stream()
                        .filter(audit -> audit.containerIds().isEmpty()
                                || audit.containerIds().stream()
                                        .anyMatch(id -> !cacheIds.contains(id)))
                        .map(audit -> new AuditSchedule(
                                audit.id(), audit.mode(), audit.interval(),
                                audit.containerIds().stream()
                                        .filter(id -> !cacheIds.contains(id)).toList()
                        )).toList(),
                source.overflowContainer()
        );
    }

    private static boolean referencesRemovedCoordinate(PickupRegion region, Set<String> removed) {
        if (removed.contains(region.standbyCoordinateId())) {
            return true;
        }
        if (region.shape() instanceof SpherePickupShape sphere) {
            return removed.contains(sphere.centerCoordinateId());
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) region.shape();
        return removed.contains(cuboid.firstCornerCoordinateId())
                || removed.contains(cuboid.secondCornerCoordinateId());
    }
}
