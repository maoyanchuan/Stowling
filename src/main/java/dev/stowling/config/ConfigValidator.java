package dev.stowling.config;

import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static List<ConfigViolation> validate(StowlingConfig config) {
        Objects.requireNonNull(config, "config");
        List<ConfigViolation> violations = new ArrayList<>();
        Map<String, ContainerRef> containers = new HashMap<>();
        Map<String, String> pickupSiteIds = new HashMap<>();
        Map<String, String> stationIds = new HashMap<>();
        Map<String, String> routeIds = new HashMap<>();
        Map<String, String> auditIds = new HashMap<>();

        registerContainer(
                violations,
                containers,
                "overflowContainer",
                config.overflowContainer()
        );

        for (int index = 0; index < config.pickupSites().size(); index++) {
            PickupSite site = config.pickupSites().get(index);
            String path = "pickupSites[" + index + "]";
            requireUnique(violations, pickupSiteIds, site.id(), path + ".id");
            registerContainer(
                    violations,
                    containers,
                    path + ".cacheContainer",
                    site.cacheContainer()
            );
            if (!site.dimension().equals(site.cacheContainer().dimension())) {
                violations.add(new ConfigViolation(
                        path + ".cacheContainer.dimension",
                        "cache container must be in the pickup site dimension"
                ));
            }
        }

        for (int index = 0; index < config.packingStations().size(); index++) {
            PackingStation station = config.packingStations().get(index);
            String path = "packingStations[" + index + "]";
            requireUnique(violations, stationIds, station.id(), path + ".id");
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

        Map<String, Map<Integer, String>> itemPriorities = new HashMap<>();
        Map<String, Map<Integer, String>> tagPriorities = new HashMap<>();
        for (int index = 0; index < config.storageRoutes().size(); index++) {
            StorageRoute route = config.storageRoutes().get(index);
            String path = "storageRoutes[" + index + "]";
            requireUnique(violations, routeIds, route.id(), path + ".id");
            registerContainer(
                    violations,
                    containers,
                    path + ".bulkContainer",
                    route.bulkContainer()
            );
            if (route.packingEnabled() && !stationIds.containsKey(route.packingStationId())) {
                violations.add(new ConfigViolation(
                        path + ".packingStationId",
                        "packing station does not exist"
                ));
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

        for (int index = 0; index < config.auditSchedules().size(); index++) {
            AuditSchedule audit = config.auditSchedules().get(index);
            String path = "auditSchedules[" + index + "]";
            requireUnique(violations, auditIds, audit.id(), path + ".id");
            for (String containerId : audit.containerIds()) {
                if (!containers.containsKey(containerId)) {
                    violations.add(new ConfigViolation(
                            path + ".containerIds",
                            "unknown container id: " + containerId
                    ));
                }
            }
        }
        return List.copyOf(violations);
    }

    public static void requireValid(StowlingConfig config) {
        List<ConfigViolation> violations = validate(config);
        if (!violations.isEmpty()) {
            throw new ConfigValidationException(violations);
        }
    }

    private static void registerContainer(
            List<ConfigViolation> violations,
            Map<String, ContainerRef> containers,
            String path,
            ContainerRef container
    ) {
        ContainerRef previous = containers.putIfAbsent(container.id(), container);
        if (previous != null && !previous.equals(container)) {
            violations.add(new ConfigViolation(
                    path,
                    "container id is reused with conflicting metadata: " + container.id()
            ));
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
            Map<Integer, String> routesByPriority = priorities.get(selector);
            if (routesByPriority == null) {
                routesByPriority = new HashMap<>();
                priorities.put(selector, routesByPriority);
            }
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
