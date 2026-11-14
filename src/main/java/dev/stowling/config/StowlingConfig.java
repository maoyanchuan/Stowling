package dev.stowling.config;

import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record StowlingConfig(
        int schemaVersion,
        List<NamedCoordinate> coordinatePoints,
        List<PickupRegion> pickupRegions,
        List<NamedCache> cacheContainers,
        List<PickupWorkflow> pickupWorkflows,
        List<StorageRoute> storageRoutes,
        List<PackingStation> packingStations,
        List<AuditSchedule> auditSchedules,
        ContainerRef overflowContainer
) {
    public StowlingConfig {
        if (schemaVersion != 3) {
            throw new IllegalArgumentException(
                    "unsupported config schemaVersion: " + schemaVersion
            );
        }
        coordinatePoints = copy(coordinatePoints, "coordinatePoints");
        pickupRegions = copy(pickupRegions, "pickupRegions");
        cacheContainers = copy(cacheContainers, "cacheContainers");
        pickupWorkflows = copy(pickupWorkflows, "pickupWorkflows");
        storageRoutes = copy(storageRoutes, "storageRoutes");
        packingStations = copy(packingStations, "packingStations");
        auditSchedules = copy(auditSchedules, "auditSchedules");
        overflowContainer = Objects.requireNonNull(overflowContainer, "overflowContainer");
    }

    public StowlingConfig(
            int schemaVersion,
            List<PickupRegion> pickupRegions,
            List<NamedCache> cacheContainers,
            List<PickupWorkflow> pickupWorkflows,
            List<StorageRoute> storageRoutes,
            List<PackingStation> packingStations,
            List<AuditSchedule> auditSchedules,
            ContainerRef overflowContainer
    ) {
        this(
                upgradeResources(
                        schemaVersion,
                        pickupRegions,
                        cacheContainers,
                        pickupWorkflows
                ),
                storageRoutes,
                packingStations,
                auditSchedules,
                overflowContainer
        );
    }

    public StowlingConfig(
            int schemaVersion,
            Duration defaultIdleDelay,
            List<PickupSite> pickupSites,
            List<StorageRoute> storageRoutes,
            List<PackingStation> packingStations,
            List<AuditSchedule> auditSchedules,
            ContainerRef overflowContainer
    ) {
        this(
                legacyResources(schemaVersion, defaultIdleDelay, pickupSites),
                storageRoutes,
                packingStations,
                auditSchedules,
                overflowContainer
        );
    }

    private StowlingConfig(
            CoordinateResources resources,
            List<StorageRoute> storageRoutes,
            List<PackingStation> packingStations,
            List<AuditSchedule> auditSchedules,
            ContainerRef overflowContainer
    ) {
        this(
                3,
                resources.coordinates(),
                resources.regions(),
                resources.caches(),
                resources.workflows(),
                storageRoutes,
                packingStations,
                auditSchedules,
                overflowContainer
        );
    }

    public Duration defaultIdleDelay() {
        return pickupWorkflows.stream()
                .flatMap(workflow -> workflow.regions().stream())
                .map(PickupRegionBinding::idleDelay)
                .findFirst()
                .orElse(Duration.ZERO);
    }

    public List<PickupSite> pickupSites() {
        Map<String, NamedCache> cachesById = new LinkedHashMap<>();
        for (NamedCache cache : cacheContainers) {
            cachesById.put(cache.id(), cache);
        }
        Map<String, PickupWorkflow> workflowByRegion = new LinkedHashMap<>();
        for (PickupWorkflow workflow : pickupWorkflows) {
            for (PickupRegionBinding binding : workflow.regions()) {
                workflowByRegion.putIfAbsent(binding.regionId(), workflow);
            }
        }
        List<PickupSite> sites = new ArrayList<>();
        for (PickupRegion region : pickupRegions) {
            PickupWorkflow workflow = workflowByRegion.get(region.id());
            if (workflow == null) {
                continue;
            }
            PickupRegionBinding binding = workflow.regions().stream()
                    .filter(candidate -> candidate.regionId().equals(region.id()))
                    .findFirst()
                    .orElseThrow();
            List<ContainerRef> caches = workflow.cacheIds().stream()
                    .map(cachesById::get)
                    .filter(Objects::nonNull)
                    .map(NamedCache::toContainerRef)
                    .toList();
            sites.add(toLegacySite(region, binding, caches));
        }
        return List.copyOf(sites);
    }

    private static <T> List<T> copy(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }

    private static PickupSite toLegacySite(
            PickupRegion region,
            PickupRegionBinding binding,
            List<ContainerRef> caches
    ) {
        if (region.shape() instanceof SpherePickupShape sphere) {
            return new PickupSite(
                    region.id(),
                    region.dimension(),
                    sphere.center(),
                    sphere.radius(),
                    caches,
                    binding.idleDelay()
            );
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) region.shape();
        BlockPosition center = new BlockPosition(
                midpoint(cuboid.firstCorner().x(), cuboid.secondCorner().x()),
                midpoint(cuboid.firstCorner().y(), cuboid.secondCorner().y()),
                midpoint(cuboid.firstCorner().z(), cuboid.secondCorner().z())
        );
        int radius = Math.max(1, Math.min(128, maximumHalfExtent(cuboid)));
        return new PickupSite(
                region.id(),
                region.dimension(),
                center,
                radius,
                caches,
                binding.idleDelay()
        );
    }

    private static int midpoint(int first, int second) {
        return (int) (((long) first + second) / 2L);
    }

    private static int maximumHalfExtent(CuboidPickupShape cuboid) {
        int x = cuboid.secondCorner().x() - cuboid.firstCorner().x();
        int y = cuboid.secondCorner().y() - cuboid.firstCorner().y();
        int z = cuboid.secondCorner().z() - cuboid.firstCorner().z();
        return Math.max(x, Math.max(y, z)) / 2 + 1;
    }

    private static CoordinateResources legacyResources(
            int schemaVersion,
            Duration defaultIdleDelay,
            List<PickupSite> pickupSites
    ) {
        if (schemaVersion < 1 || schemaVersion > 3) {
            throw new IllegalArgumentException(
                    "unsupported legacy config schemaVersion: " + schemaVersion
            );
        }
        Objects.requireNonNull(defaultIdleDelay, "defaultIdleDelay");
        Objects.requireNonNull(pickupSites, "pickupSites");
        List<PickupRegion> regions = new ArrayList<>();
        Map<String, NamedCache> caches = new LinkedHashMap<>();
        List<PickupWorkflow> workflows = new ArrayList<>();
        for (PickupSite site : pickupSites) {
            regions.add(new PickupRegion(
                    site.id(),
                    site.id(),
                    site.dimension(),
                    site.center(),
                    new SpherePickupShape(site.center(), site.radius())
            ));
            List<String> cacheIds = new ArrayList<>();
            for (ContainerRef container : site.cacheContainers()) {
                caches.putIfAbsent(container.id(), new NamedCache(
                        container.id(),
                        container.id(),
                        container.dimension(),
                        container.position(),
                        container.type()
                ));
                if (!cacheIds.contains(container.id())) {
                    cacheIds.add(container.id());
                }
            }
            workflows.add(new PickupWorkflow(
                    site.id() + "-workflow",
                    site.id(),
                    site.dimension(),
                    List.of(new PickupRegionBinding(site.id(), 100, site.idleDelay())),
                    cacheIds,
                    CachePolicy.NEAREST_AVAILABLE,
                    ClassificationTiming.AFTER_ROUND,
                    CompletionMode.ALL_IDLE,
                    site.idleDelay()
            ));
        }
        return upgradeResources(
                2,
                regions,
                List.copyOf(caches.values()),
                workflows
        );
    }

    private static CoordinateResources upgradeResources(
            int schemaVersion,
            List<PickupRegion> regions,
            List<NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
        if (schemaVersion < 2 || schemaVersion > 3) {
            throw new IllegalArgumentException(
                    "unsupported config schemaVersion: " + schemaVersion
            );
        }
        CoordinateBuilder coordinates = new CoordinateBuilder(
                resourceIds(regions, caches, workflows)
        );
        List<PickupRegion> upgradedRegions = regions.stream()
                .map(region -> upgradeRegion(region, coordinates))
                .toList();
        List<NamedCache> upgradedCaches = caches.stream()
                .map(cache -> upgradeCache(cache, coordinates))
                .toList();
        return new CoordinateResources(
                coordinates.values(),
                upgradedRegions,
                upgradedCaches,
                List.copyOf(workflows)
        );
    }

    private static Set<String> resourceIds(
            List<PickupRegion> regions,
            List<NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
        Set<String> ids = new LinkedHashSet<>();
        regions.forEach(region -> ids.add(region.id()));
        caches.forEach(cache -> ids.add(cache.id()));
        workflows.forEach(workflow -> ids.add(workflow.id()));
        return ids;
    }

    private static PickupRegion upgradeRegion(
            PickupRegion region,
            CoordinateBuilder coordinates
    ) {
        NamedCoordinate standby = coordinates.resolve(
                region.standbyCoordinateId(),
                region.name() + " standby",
                region.dimension(),
                region.standbyPosition()
        );
        PickupShape shape = upgradeShape(region, coordinates);
        return new PickupRegion(
                region.id(),
                region.name(),
                standby.dimension(),
                standby.id(),
                standby.position(),
                shape
        );
    }

    private static PickupShape upgradeShape(
            PickupRegion region,
            CoordinateBuilder coordinates
    ) {
        if (region.shape() instanceof SpherePickupShape sphere) {
            NamedCoordinate center = coordinates.resolve(
                    sphere.centerCoordinateId(),
                    region.name() + " center",
                    region.dimension(),
                    sphere.center()
            );
            return new SpherePickupShape(center.id(), center.position(), sphere.radius());
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) region.shape();
        NamedCoordinate first = coordinates.resolve(
                cuboid.firstCornerCoordinateId(),
                region.name() + " first corner",
                region.dimension(),
                cuboid.firstCorner()
        );
        NamedCoordinate second = coordinates.resolve(
                cuboid.secondCornerCoordinateId(),
                region.name() + " second corner",
                region.dimension(),
                cuboid.secondCorner()
        );
        return new CuboidPickupShape(
                first.id(),
                second.id(),
                first.position(),
                second.position()
        );
    }

    private static NamedCache upgradeCache(
            NamedCache cache,
            CoordinateBuilder coordinates
    ) {
        NamedCoordinate coordinate = coordinates.resolve(
                cache.coordinateId(),
                cache.name() + " position",
                cache.dimension(),
                cache.position()
        );
        return new NamedCache(
                cache.id(),
                cache.name(),
                coordinate.id(),
                coordinate.dimension(),
                coordinate.position(),
                cache.type()
        );
    }

    private record CoordinateResources(
            List<NamedCoordinate> coordinates,
            List<PickupRegion> regions,
            List<NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
    }

    private record CoordinateKey(String dimension, BlockPosition position) {
    }

    private static final class CoordinateBuilder {
        private final Map<CoordinateKey, NamedCoordinate> coordinates = new LinkedHashMap<>();
        private final Set<String> usedIds;

        private CoordinateBuilder(Set<String> usedIds) {
            this.usedIds = new LinkedHashSet<>(usedIds);
        }

        private NamedCoordinate resolve(
                String preferredId,
                String name,
                String dimension,
                BlockPosition position
        ) {
            CoordinateKey key = new CoordinateKey(dimension, position);
            NamedCoordinate existing = coordinates.get(key);
            if (existing != null) {
                return existing;
            }
            String id = stableCoordinateId(preferredId, name, usedIds);
            NamedCoordinate coordinate = new NamedCoordinate(id, name, dimension, position);
            coordinates.put(key, coordinate);
            return coordinate;
        }

        private List<NamedCoordinate> values() {
            return List.copyOf(coordinates.values());
        }
    }

    private static String stableCoordinateId(
            String preferredId,
            String name,
            Set<String> usedIds
    ) {
        String base = normalizeId(preferredId);
        if (base.isEmpty()) {
            base = normalizeId(name);
        }
        if (base.isEmpty()) {
            base = "coordinate";
        }
        String candidate = truncateId(base, 64);
        int suffix = 2;
        while (!usedIds.add(candidate)) {
            String suffixText = "-" + suffix;
            candidate = truncateId(base, 64 - suffixText.length()) + suffixText;
            suffix++;
        }
        return candidate;
    }

    private static String normalizeId(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("-+$", "");
    }

    private static String truncateId(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
