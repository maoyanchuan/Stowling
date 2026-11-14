package dev.stowling.config;

import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StowlingConfig(
        int schemaVersion,
        List<PickupRegion> pickupRegions,
        List<NamedCache> cacheContainers,
        List<PickupWorkflow> pickupWorkflows,
        List<StorageRoute> storageRoutes,
        List<PackingStation> packingStations,
        List<AuditSchedule> auditSchedules,
        ContainerRef overflowContainer
) {
    public StowlingConfig {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException(
                    "unsupported config schemaVersion: " + schemaVersion
            );
        }
        pickupRegions = List.copyOf(Objects.requireNonNull(
                pickupRegions,
                "pickupRegions"
        ));
        cacheContainers = List.copyOf(Objects.requireNonNull(
                cacheContainers,
                "cacheContainers"
        ));
        pickupWorkflows = List.copyOf(Objects.requireNonNull(
                pickupWorkflows,
                "pickupWorkflows"
        ));
        storageRoutes = List.copyOf(Objects.requireNonNull(storageRoutes, "storageRoutes"));
        packingStations = List.copyOf(Objects.requireNonNull(
                packingStations,
                "packingStations"
        ));
        auditSchedules = List.copyOf(Objects.requireNonNull(auditSchedules, "auditSchedules"));
        overflowContainer = Objects.requireNonNull(overflowContainer, "overflowContainer");
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
            LegacyResources resources,
            List<StorageRoute> storageRoutes,
            List<PackingStation> packingStations,
            List<AuditSchedule> auditSchedules,
            ContainerRef overflowContainer
    ) {
        this(
                2,
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

    private static LegacyResources legacyResources(
            int schemaVersion,
            Duration defaultIdleDelay,
            List<PickupSite> pickupSites
    ) {
        if (schemaVersion != 1 && schemaVersion != 2) {
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
        return new LegacyResources(regions, List.copyOf(caches.values()), workflows);
    }

    private record LegacyResources(
            List<PickupRegion> regions,
            List<NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
    }
}
