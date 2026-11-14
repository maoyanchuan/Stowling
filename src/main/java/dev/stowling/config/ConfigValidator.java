package dev.stowling.config;

import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigValidator {
    private static final Pattern SAFE_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}"
    );

    private ConfigValidator() {
    }

    public static List<ConfigViolation> validate(StowlingConfig config) {
        Objects.requireNonNull(config, "config");
        List<ConfigViolation> violations = new ArrayList<>();
        Map<String, ContainerRef> containers = new HashMap<>();
        Map<String, String> resourceIds = new HashMap<>();
        Map<String, PickupRegion> regions = new HashMap<>();
        Map<String, NamedCache> caches = new HashMap<>();
        Map<String, NamedCoordinate> coordinates = new HashMap<>();

        registerContainer(
                violations,
                containers,
                "overflowContainer",
                config.overflowContainer()
        );
        requireSafeId(
                violations,
                config.overflowContainer().id(),
                "overflowContainer.id"
        );

        validateCoordinates(
                violations,
                resourceIds,
                coordinates,
                config.coordinatePoints()
        );
        validatePickupRegions(
                violations,
                resourceIds,
                coordinates,
                regions,
                config.pickupRegions()
        );
        validateCaches(
                violations,
                resourceIds,
                containers,
                coordinates,
                caches,
                config.cacheContainers()
        );
        validateWorkflows(
                violations,
                resourceIds,
                regions,
                caches,
                config.pickupWorkflows()
        );
        validatePackingStations(
                violations,
                containers,
                config.packingStations()
        );
        validateStorageRoutes(
                violations,
                containers,
                config.packingStations(),
                config.storageRoutes()
        );
        validateAudits(violations, containers, config.auditSchedules());
        return List.copyOf(violations);
    }

    public static void requireValid(StowlingConfig config) {
        List<ConfigViolation> violations = validate(config);
        if (!violations.isEmpty()) {
            throw new ConfigValidationException(violations);
        }
    }

    private static void validateCoordinates(
            List<ConfigViolation> violations,
            Map<String, String> resourceIds,
            Map<String, NamedCoordinate> coordinates,
            List<NamedCoordinate> coordinatePoints
    ) {
        for (int index = 0; index < coordinatePoints.size(); index++) {
            NamedCoordinate coordinate = coordinatePoints.get(index);
            String path = "coordinatePoints[" + index + "]";
            registerResourceId(violations, resourceIds, coordinate.id(), path + ".id");
            requireName(violations, coordinate.name(), path + ".name");
            coordinates.put(coordinate.id(), coordinate);
        }
    }

    private static void validatePickupRegions(
            List<ConfigViolation> violations,
            Map<String, String> resourceIds,
            Map<String, NamedCoordinate> coordinates,
            Map<String, PickupRegion> regions,
            List<PickupRegion> pickupRegions
    ) {
        for (int index = 0; index < pickupRegions.size(); index++) {
            PickupRegion region = pickupRegions.get(index);
            String path = "pickupRegions[" + index + "]";
            registerResourceId(violations, resourceIds, region.id(), path + ".id");
            requireName(violations, region.name(), path + ".name");
            validateCoordinateReference(
                    violations,
                    coordinates,
                    region.standbyCoordinateId(),
                    region.dimension(),
                    path + ".standbyCoordinateId",
                    "pickup region " + region.id()
            );
            validateShapeCoordinates(violations, coordinates, region, path);
            regions.put(region.id(), region);
        }
    }

    private static void validateShapeCoordinates(
            List<ConfigViolation> violations,
            Map<String, NamedCoordinate> coordinates,
            PickupRegion region,
            String path
    ) {
        if (region.shape() instanceof SpherePickupShape sphere) {
            validateCoordinateReference(
                    violations,
                    coordinates,
                    sphere.centerCoordinateId(),
                    region.dimension(),
                    path + ".shape.centerCoordinateId",
                    "pickup region " + region.id()
            );
            return;
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) region.shape();
        validateCoordinateReference(
                violations,
                coordinates,
                cuboid.firstCornerCoordinateId(),
                region.dimension(),
                path + ".shape.firstCornerCoordinateId",
                "pickup region " + region.id()
        );
        validateCoordinateReference(
                violations,
                coordinates,
                cuboid.secondCornerCoordinateId(),
                region.dimension(),
                path + ".shape.secondCornerCoordinateId",
                "pickup region " + region.id()
        );
    }

    private static void validateCaches(
            List<ConfigViolation> violations,
            Map<String, String> resourceIds,
            Map<String, ContainerRef> containers,
            Map<String, NamedCoordinate> coordinates,
            Map<String, NamedCache> caches,
            List<NamedCache> cacheContainers
    ) {
        for (int index = 0; index < cacheContainers.size(); index++) {
            NamedCache cache = cacheContainers.get(index);
            String path = "cacheContainers[" + index + "]";
            registerResourceId(violations, resourceIds, cache.id(), path + ".id");
            requireName(violations, cache.name(), path + ".name");
            validateCoordinateReference(
                    violations,
                    coordinates,
                    cache.coordinateId(),
                    cache.dimension(),
                    path + ".coordinateId",
                    "cache " + cache.id()
            );
            caches.put(cache.id(), cache);
            registerContainer(violations, containers, path, cache.toContainerRef());
        }
    }

    private static void validateCoordinateReference(
            List<ConfigViolation> violations,
            Map<String, NamedCoordinate> coordinates,
            String coordinateId,
            String dimension,
            String path,
            String owner
    ) {
        if (coordinateId == null || coordinateId.isBlank()) {
            violations.add(new ConfigViolation(
                    path,
                    owner + " requires a coordinate reference"
            ));
            return;
        }
        requireSafeId(violations, coordinateId, path);
        NamedCoordinate coordinate = coordinates.get(coordinateId);
        if (coordinate == null) {
            violations.add(new ConfigViolation(
                    path,
                    owner + " references unknown coordinate: " + coordinateId
            ));
        } else if (!dimension.equals(coordinate.dimension())) {
            violations.add(new ConfigViolation(
                    path,
                    owner + " coordinate must be in dimension " + dimension
            ));
        }
    }

    private static void validateWorkflows(
            List<ConfigViolation> violations,
            Map<String, String> resourceIds,
            Map<String, PickupRegion> regions,
            Map<String, NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
        for (int index = 0; index < workflows.size(); index++) {
            PickupWorkflow workflow = workflows.get(index);
            String path = "pickupWorkflows[" + index + "]";
            registerResourceId(violations, resourceIds, workflow.id(), path + ".id");
            requireName(violations, workflow.name(), path + ".name");
            if (workflow.regions().isEmpty()) {
                violations.add(new ConfigViolation(path + ".regions", "workflow is empty"));
            }
            if (workflow.cacheIds().isEmpty()) {
                violations.add(new ConfigViolation(
                        path + ".cacheIds",
                        "workflow requires at least one cache"
                ));
            }
            validateWorkflowRegions(violations, regions, workflow, path);
            validateWorkflowCaches(violations, caches, workflow, path);
        }
    }

    private static void validateWorkflowRegions(
            List<ConfigViolation> violations,
            Map<String, PickupRegion> regions,
            PickupWorkflow workflow,
            String path
    ) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < workflow.regions().size(); index++) {
            PickupRegionBinding binding = workflow.regions().get(index);
            String bindingPath = path + ".regions[" + index + "]";
            requireSafeId(violations, binding.regionId(), bindingPath + ".regionId");
            if (!seen.add(binding.regionId())) {
                violations.add(new ConfigViolation(
                        bindingPath + ".regionId",
                        "duplicate region reference"
                ));
            }
            PickupRegion region = regions.get(binding.regionId());
            if (region == null) {
                violations.add(new ConfigViolation(
                        bindingPath + ".regionId",
                        "pickup region does not exist"
                ));
            } else if (!workflow.dimension().equals(region.dimension())) {
                violations.add(new ConfigViolation(
                        bindingPath + ".regionId",
                        "pickup region must be in the workflow dimension"
                ));
            }
            if (binding.idleDelay().isNegative()) {
                violations.add(new ConfigViolation(
                        bindingPath + ".idleDelay",
                        "idle delay must not be negative"
                ));
            }
        }
    }

    private static void validateWorkflowCaches(
            List<ConfigViolation> violations,
            Map<String, NamedCache> caches,
            PickupWorkflow workflow,
            String path
    ) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < workflow.cacheIds().size(); index++) {
            String cacheId = workflow.cacheIds().get(index);
            String cachePath = path + ".cacheIds[" + index + "]";
            requireSafeId(violations, cacheId, cachePath);
            if (!seen.add(cacheId)) {
                violations.add(new ConfigViolation(cachePath, "duplicate cache reference"));
            }
            NamedCache cache = caches.get(cacheId);
            if (cache == null) {
                violations.add(new ConfigViolation(cachePath, "cache does not exist"));
            } else if (!workflow.dimension().equals(cache.dimension())) {
                violations.add(new ConfigViolation(
                        cachePath,
                        "cache must be in the workflow dimension"
                ));
            }
        }
        if (workflow.completionIdleDelay().isNegative()) {
            violations.add(new ConfigViolation(
                    path + ".completionIdleDelay",
                    "completion idle delay must not be negative"
            ));
        }
    }

    private static void validatePackingStations(
            List<ConfigViolation> violations,
            Map<String, ContainerRef> containers,
            List<PackingStation> stations
    ) {
        Map<String, String> stationIds = new HashMap<>();
        for (int index = 0; index < stations.size(); index++) {
            PackingStation station = stations.get(index);
            String path = "packingStations[" + index + "]";
            requireUnique(violations, stationIds, station.id(), path + ".id");
            requireSafeId(violations, station.id(), path + ".id");
            registerContainer(
                    violations,
                    containers,
                    path + ".productContainer",
                    station.productContainer()
            );
            if (!station.dimension().equals(station.productContainer().dimension())) {
                violations.add(new ConfigViolation(
                        path + ".productContainer.dimension",
                        "product container must be in the packing station dimension"
                ));
            }
        }
    }

    private static void validateStorageRoutes(
            List<ConfigViolation> violations,
            Map<String, ContainerRef> containers,
            List<PackingStation> stations,
            List<StorageRoute> routes
    ) {
        Set<String> stationIds = new HashSet<>();
        stations.forEach(station -> stationIds.add(station.id()));
        Map<String, String> routeIds = new HashMap<>();
        Map<String, Map<Integer, String>> itemPriorities = new HashMap<>();
        Map<String, Map<Integer, String>> tagPriorities = new HashMap<>();
        for (int index = 0; index < routes.size(); index++) {
            StorageRoute route = routes.get(index);
            String path = "storageRoutes[" + index + "]";
            requireUnique(violations, routeIds, route.id(), path + ".id");
            requireSafeId(violations, route.id(), path + ".id");
            registerContainer(
                    violations,
                    containers,
                    path + ".bulkContainer",
                    route.bulkContainer()
            );
            for (int stationIndex = 0;
                    stationIndex < route.packingStationIds().size();
                    stationIndex++) {
                String stationId = route.packingStationIds().get(stationIndex);
                if (!stationIds.contains(stationId)) {
                    violations.add(new ConfigViolation(
                            path + ".packingStationIds[" + stationIndex + "]",
                            "packing station does not exist"
                    ));
                }
            }
            registerSelectors(
                    violations,
                    itemPriorities,
                    route.itemIds(),
                    route,
                    path + ".itemIds"
            );
            registerSelectors(
                    violations,
                    tagPriorities,
                    route.itemTags(),
                    route,
                    path + ".itemTags"
            );
        }
    }

    private static void validateAudits(
            List<ConfigViolation> violations,
            Map<String, ContainerRef> containers,
            List<AuditSchedule> audits
    ) {
        Map<String, String> auditIds = new HashMap<>();
        for (int index = 0; index < audits.size(); index++) {
            AuditSchedule audit = audits.get(index);
            String path = "auditSchedules[" + index + "]";
            requireUnique(violations, auditIds, audit.id(), path + ".id");
            requireSafeId(violations, audit.id(), path + ".id");
            for (String containerId : audit.containerIds()) {
                if (!containers.containsKey(containerId)) {
                    violations.add(new ConfigViolation(
                            path + ".containerIds",
                            "unknown container id: " + containerId
                    ));
                }
            }
        }
    }

    private static void registerContainer(
            List<ConfigViolation> violations,
            Map<String, ContainerRef> containers,
            String path,
            ContainerRef container
    ) {
        requireSafeId(violations, container.id(), path + ".id");
        ContainerRef previous = containers.putIfAbsent(container.id(), container);
        if (previous != null && !previous.equals(container)) {
            violations.add(new ConfigViolation(
                    path,
                    "container id is reused with conflicting metadata: " + container.id()
            ));
        }
    }

    private static void registerResourceId(
            List<ConfigViolation> violations,
            Map<String, String> seen,
            String id,
            String path
    ) {
        requireSafeId(violations, id, path);
        requireUnique(violations, seen, id, path);
    }

    private static void requireSafeId(
            List<ConfigViolation> violations,
            String id,
            String path
    ) {
        if (!SAFE_ID.matcher(id).matches()) {
            violations.add(new ConfigViolation(
                    path,
                    "id must match [a-z0-9][a-z0-9._-]{0,63}"
            ));
        }
    }

    private static void requireName(
            List<ConfigViolation> violations,
            String name,
            String path
    ) {
        if (name.isBlank()) {
            violations.add(new ConfigViolation(path, "name must not be blank"));
        }
    }

    private static void requireUnique(
            List<ConfigViolation> violations,
            Map<String, String> seen,
            String id,
            String path
    ) {
        String previousPath = seen.putIfAbsent(id, path);
        if (previousPath != null) {
            violations.add(new ConfigViolation(
                    path,
                    "duplicate id already declared at " + previousPath
            ));
        }
    }

    private static void registerSelectors(
            List<ConfigViolation> violations,
            Map<String, Map<Integer, String>> priorities,
            List<String> selectors,
            StorageRoute route,
            String path
    ) {
        for (String selector : selectors) {
            Map<Integer, String> routesByPriority = priorities.computeIfAbsent(
                    selector,
                    ignored -> new HashMap<>()
            );
            String previousRoute = routesByPriority.putIfAbsent(route.priority(), route.id());
            if (previousRoute != null) {
                String message = String.format(
                        "ambiguous selector %s at priority %d; already claimed by route %s",
                        selector,
                        route.priority(),
                        previousRoute
                );
                violations.add(new ConfigViolation(path, message));
            }
        }
    }

}
