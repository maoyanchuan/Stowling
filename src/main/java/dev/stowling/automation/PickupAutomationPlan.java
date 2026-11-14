package dev.stowling.automation;

import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PickupAutomationPlan(
        UUID taskId,
        PickupWorkflow workflow,
        Optional<String> regionOverride,
        Map<String, PickupRegion> regionsById,
        Map<String, ContainerRef> cachesById,
        List<StorageRoute> storageRoutes,
        ContainerRef overflowContainer,
        int inventoryReserveSlots,
        int maxContainerRetries,
        Duration containerAckTimeout,
        Duration navigationTimeout,
        boolean collectOnly
) {
    public PickupAutomationPlan {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(workflow, "workflow");
        regionOverride = Objects.requireNonNull(regionOverride, "regionOverride");
        regionsById = Map.copyOf(Objects.requireNonNull(regionsById, "regionsById"));
        cachesById = Map.copyOf(Objects.requireNonNull(cachesById, "cachesById"));
        storageRoutes = List.copyOf(Objects.requireNonNull(storageRoutes, "storageRoutes"));
        if (!collectOnly) {
            Objects.requireNonNull(overflowContainer, "overflowContainer");
        }
        Objects.requireNonNull(containerAckTimeout, "containerAckTimeout");
        Objects.requireNonNull(navigationTimeout, "navigationTimeout");
        validateReferences(
                workflow,
                regionOverride,
                regionsById,
                cachesById,
                collectOnly
        );
        if (inventoryReserveSlots < 0 || inventoryReserveSlots >= 36) {
            throw new IllegalArgumentException("inventoryReserveSlots must be between 0 and 35");
        }
        if (maxContainerRetries < 0 || maxContainerRetries > 20) {
            throw new IllegalArgumentException("maxContainerRetries must be between 0 and 20");
        }
        if (containerAckTimeout.isNegative() || containerAckTimeout.isZero()) {
            throw new IllegalArgumentException("containerAckTimeout must be positive");
        }
        if (navigationTimeout.isNegative() || navigationTimeout.isZero()) {
            throw new IllegalArgumentException("navigationTimeout must be positive");
        }
    }

    public PickupAutomationPlan(
            UUID taskId,
            PickupWorkflow workflow,
            Optional<String> regionOverride,
            Map<String, PickupRegion> regionsById,
            Map<String, ContainerRef> cachesById,
            List<StorageRoute> storageRoutes,
            ContainerRef overflowContainer,
            int inventoryReserveSlots,
            int maxContainerRetries,
            Duration containerAckTimeout,
            Duration navigationTimeout
    ) {
        this(
                taskId,
                workflow,
                regionOverride,
                regionsById,
                cachesById,
                storageRoutes,
                overflowContainer,
                inventoryReserveSlots,
                maxContainerRetries,
                containerAckTimeout,
                navigationTimeout,
                false
        );
    }

    public PickupAutomationPlan(
            UUID taskId,
            PickupSite pickupSite,
            List<StorageRoute> storageRoutes,
            ContainerRef overflowContainer,
            int inventoryReserveSlots,
            int maxContainerRetries,
            Duration containerAckTimeout,
            Duration navigationTimeout
    ) {
        this(
                taskId,
                legacyWorkflow(pickupSite),
                Optional.empty(),
                Map.of(pickupSite.id(), legacyRegion(pickupSite)),
                legacyCaches(pickupSite),
                storageRoutes,
                overflowContainer,
                inventoryReserveSlots,
                maxContainerRetries,
                containerAckTimeout,
                navigationTimeout
        );
    }

    public List<PickupRegionBinding> orderedRegions() {
        return workflow.regions().stream()
                .filter(binding -> regionOverride.isEmpty()
                        || binding.regionId().equals(regionOverride.orElseThrow()))
                .sorted((first, second) -> Integer.compare(
                        second.priority(),
                        first.priority()
                ))
                .toList();
    }

    public List<ContainerRef> caches() {
        return workflow.cacheIds().stream().map(cachesById::get).toList();
    }

    private static void validateReferences(
            PickupWorkflow workflow,
            Optional<String> regionOverride,
            Map<String, PickupRegion> regionsById,
            Map<String, ContainerRef> cachesById,
            boolean collectOnly
    ) {
        if (workflow.regions().isEmpty()) {
            throw new IllegalArgumentException("workflow must contain a region");
        }
        if (!collectOnly && workflow.cacheIds().isEmpty()) {
            throw new IllegalArgumentException("workflow must contain a cache");
        }
        for (PickupRegionBinding binding : workflow.regions()) {
            if (!regionsById.containsKey(binding.regionId())) {
                throw new IllegalArgumentException("unknown region: " + binding.regionId());
            }
        }
        for (String cacheId : workflow.cacheIds()) {
            if (!cachesById.containsKey(cacheId)) {
                throw new IllegalArgumentException("unknown cache: " + cacheId);
            }
        }
        if (regionOverride.isPresent() && workflow.regions().stream().noneMatch(
                binding -> binding.regionId().equals(regionOverride.orElseThrow())
        )) {
            throw new IllegalArgumentException("region override is not in the workflow");
        }
    }

    private static PickupRegion legacyRegion(PickupSite site) {
        return new PickupRegion(
                site.id(),
                site.id(),
                site.dimension(),
                site.center(),
                new SpherePickupShape(site.center(), site.radius())
        );
    }

    private static PickupWorkflow legacyWorkflow(PickupSite site) {
        return new PickupWorkflow(
                site.id(),
                site.id(),
                site.dimension(),
                List.of(new PickupRegionBinding(site.id(), 100, site.idleDelay())),
                site.cacheContainers().stream().map(ContainerRef::id).toList(),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ZERO
        );
    }

    private static Map<String, ContainerRef> legacyCaches(PickupSite site) {
        Map<String, ContainerRef> result = new LinkedHashMap<>();
        for (ContainerRef cache : site.cacheContainers()) {
            result.put(cache.id(), cache);
        }
        return Map.copyOf(result);
    }
}
