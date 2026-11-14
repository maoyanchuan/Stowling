package dev.stowling.config;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.TaskSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class LegacyPickupDefaults {
    private static final String REGION_ID = "north-farm";
    private static final String CACHE_ID = "north-cache";
    private static final Set<String> WORKFLOW_IDS = Set.of(
            "farm-patrol", "north-farm-workflow"
    );
    private static final Set<String> COORDINATE_IDS = Set.of(
            "north-farm-standby", "north-farm-center", "north-cache-position",
            "north-farm-first-corner", "north-farm-second-corner"
    );

    private LegacyPickupDefaults() {
    }

    /**
     * Retires only the old north example without reading or writing configuration files.
     * Returns the original configuration when a surviving task needs the example dependencies.
     */
    public static StowlingConfig remove(StowlingConfig source) {
        Objects.requireNonNull(source, "source");
        Set<String> removedWorkflowIds = new HashSet<>();
        List<PickupWorkflow> workflows = new ArrayList<>();
        for (PickupWorkflow workflow : source.pickupWorkflows()) {
            if (WORKFLOW_IDS.contains(workflow.id())
                    || workflow.regions().stream()
                            .anyMatch(binding -> REGION_ID.equals(binding.regionId()))
                    || workflow.cacheIds().contains(CACHE_ID)) {
                removedWorkflowIds.add(workflow.id());
            } else {
                workflows.add(workflow);
            }
        }
        for (AutomationWorkflow workflow : source.automationWorkflows()) {
            if (usesDefaults(workflow.steps(), removedWorkflowIds)
                    || usesDefaults(workflow.settings(), removedWorkflowIds)) {
                return source;
            }
        }
        for (PickupWorkflow workflow : workflows) {
            if (usesDefaults(workflow.settings(), removedWorkflowIds)) {
                return source;
            }
        }

        List<PickupRegion> regions = source.pickupRegions().stream()
                .filter(region -> !REGION_ID.equals(region.id())).toList();
        List<NamedCache> caches = source.cacheContainers().stream()
                .filter(cache -> !CACHE_ID.equals(cache.id())).toList();
        Set<String> retainedCoordinates = new HashSet<>();
        for (PickupRegion region : regions) {
            retainShapeCoordinates(retainedCoordinates, region);
        }
        for (NamedCache cache : caches) {
            retain(retainedCoordinates, cache.coordinateId());
        }
        List<PickupRegion> anchoredRegions = new ArrayList<>();
        for (PickupRegion region : regions) {
            PickupRegion anchored = reanchor(source, region, retainedCoordinates);
            anchoredRegions.add(anchored);
            retain(retainedCoordinates, anchored.standbyCoordinateId());
        }
        List<NamedCoordinate> coordinates = source.coordinatePoints().stream()
                .filter(point -> !COORDINATE_IDS.contains(point.id())
                        || retainedCoordinates.contains(point.id())).toList();
        boolean removedCache = source.cacheContainers().stream()
                .anyMatch(cache -> CACHE_ID.equals(cache.id()));
        List<AuditSchedule> audits = new ArrayList<>();
        for (AuditSchedule audit : source.auditSchedules()) {
            if ("manual-check".equals(audit.id())) {
                continue;
            }
            List<String> containers = audit.containerIds().stream()
                    .filter(id -> !removedCache || !CACHE_ID.equals(id)).toList();
            if (!containers.isEmpty() || audit.containerIds().isEmpty()) {
                audits.add(containers.equals(audit.containerIds()) ? audit : new AuditSchedule(
                        audit.id(), audit.mode(), audit.interval(), containers
                ));
            }
        }
        StowlingConfig updated = new StowlingConfig(
                source.schemaVersion(), coordinates, anchoredRegions, caches, workflows,
                source.automationWorkflows(), source.storageRoutes(), source.packingStations(),
                audits, source.overflowContainer()
        );
        return updated.equals(source) ? source : updated;
    }

    private static boolean usesDefaults(TaskSettings settings, Set<String> removedWorkflowIds) {
        return settings.trigger().regionIds().contains(REGION_ID)
                || usesDefaults(settings.interruption().steps(), removedWorkflowIds);
    }

    private static boolean usesDefaults(
            List<AutomationStep> steps,
            Set<String> removedWorkflowIds
    ) {
        for (AutomationStep step : steps) {
            boolean uses = switch (step.type()) {
                case NAVIGATE -> referencesCoordinate(step, "coordinateId");
                case RUN_PICKUP -> removedWorkflowIds.contains(step.parameter("workflowId"))
                        || REGION_ID.equals(step.parameter("regionId"))
                        || referencesCoordinate(step, "standbyCoordinateId",
                                "firstCornerCoordinateId", "secondCornerCoordinateId")
                        || referencesCacheList(step.parameter("cacheIds"));
                case TAKE_ITEMS, PUT_ITEMS -> referencesCoordinate(step, "coordinateId")
                        || CACHE_ID.equals(step.parameter("containerId"));
                case TRANSFER_ITEMS -> referencesCoordinate(step,
                        "sourceCoordinateId", "destinationCoordinateId")
                        || CACHE_ID.equals(step.parameter("sourceContainerId"))
                        || CACHE_ID.equals(step.parameter("destination"));
                case PACK_ITEMS -> referencesCoordinate(step, "sourceCoordinateId",
                        "boxCoordinateId", "outputCoordinateId", "fallbackCoordinateId");
                default -> false;
            };
            if (uses) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesCoordinate(AutomationStep step, String... keys) {
        for (String key : keys) {
            String value = step.parameter(key);
            if (value != null && COORDINATE_IDS.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesCacheList(String value) {
        if (value == null) {
            return false;
        }
        for (String id : value.split(",")) {
            if (CACHE_ID.equals(id.trim())) {
                return true;
            }
        }
        return false;
    }

    private static void retainShapeCoordinates(Set<String> ids, PickupRegion region) {
        if (region.shape() instanceof SpherePickupShape sphere) {
            retain(ids, sphere.centerCoordinateId());
        } else if (region.shape() instanceof CuboidPickupShape cuboid) {
            retain(ids, cuboid.firstCornerCoordinateId());
            retain(ids, cuboid.secondCornerCoordinateId());
        }
    }

    private static PickupRegion reanchor(
            StowlingConfig source,
            PickupRegion region,
            Set<String> retainedCoordinates
    ) {
        String standbyId = region.standbyCoordinateId();
        if (standbyId == null || !COORDINATE_IDS.contains(standbyId)
                || retainedCoordinates.contains(standbyId)) {
            return region;
        }
        String anchorId = region.shape() instanceof SpherePickupShape sphere
                ? sphere.centerCoordinateId()
                : ((CuboidPickupShape) region.shape()).firstCornerCoordinateId();
        NamedCoordinate anchor = source.coordinatePoints().stream()
                .filter(point -> point.id().equals(anchorId)
                        && point.dimension().equals(region.dimension()))
                .findFirst().orElse(null);
        if (anchor == null) {
            return region;
        }
        return new PickupRegion(region.id(), region.name(), region.dimension(),
                anchor.id(), anchor.position(), region.shape());
    }

    private static void retain(Set<String> ids, String id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
