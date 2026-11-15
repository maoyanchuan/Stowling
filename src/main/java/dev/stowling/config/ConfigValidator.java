package dev.stowling.config;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.automation.CustomPickupRules;
import dev.stowling.domain.AuditSchedule;
import dev.stowling.automation.module.TaskContainerParameters;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.TriggerKind;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ConfigValidator {
    private static final Pattern SAFE_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}"
    );
    private static final Pattern TIME_OF_DAY = Pattern.compile(
            "(?:[01][0-9]|2[0-3]):[0-5][0-9]"
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
        validateAutomationWorkflows(
                violations,
                resourceIds,
                coordinates,
                containers,
                config.pickupWorkflows(),
                config.automationWorkflows(),
                config
        );
        validateTaskSettings(
                violations,
                regions,
                coordinates,
                containers,
                config.pickupWorkflows(),
                config.automationWorkflows(),
                config
        );
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
            validateCoordinateShape(violations, coordinate, path);
            coordinates.put(coordinate.id(), coordinate);
        }
    }

    private static void validateCoordinateShape(
            List<ConfigViolation> violations,
            NamedCoordinate coordinate,
            String path
    ) {
        switch (coordinate.type()) {
            case POINT -> {
                rejectCoordinateField(violations, coordinate.radius(), path + ".radius");
                rejectCoordinateField(
                        violations,
                        coordinate.secondCorner(),
                        path + ".secondCorner"
                );
            }
            case SPHERE, CUBE -> {
                if (coordinate.radius() == null
                        || coordinate.radius() < 1
                        || coordinate.radius() > 128) {
                    violations.add(new ConfigViolation(
                            path + ".radius",
                            "radius must be between 1 and 128 for " + coordinate.type()
                    ));
                }
                rejectCoordinateField(
                        violations,
                        coordinate.secondCorner(),
                        path + ".secondCorner"
                );
            }
            case AREA -> {
                rejectCoordinateField(violations, coordinate.radius(), path + ".radius");
                if (coordinate.secondCorner() == null) {
                    violations.add(new ConfigViolation(
                            path + ".secondCorner",
                            "secondCorner is required for AREA"
                    ));
                }
            }
            default -> throw new IllegalStateException("unsupported coordinate type");
        }
    }

    private static void rejectCoordinateField(
            List<ConfigViolation> violations,
            Object value,
            String path
    ) {
        if (value != null) {
            violations.add(new ConfigViolation(
                    path,
                    "field is not valid for this coordinate type"
            ));
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

    private static void validateAutomationWorkflows(
            List<ConfigViolation> violations,
            Map<String, String> resourceIds,
            Map<String, NamedCoordinate> coordinates,
            Map<String, ContainerRef> containers,
            List<PickupWorkflow> pickupWorkflows,
            List<AutomationWorkflow> workflows,
            StowlingConfig config
    ) {
        Map<String, PickupWorkflow> pickupsById = new HashMap<>();
        for (PickupWorkflow pickupWorkflow : pickupWorkflows) {
            pickupsById.put(pickupWorkflow.id(), pickupWorkflow);
        }
        for (int workflowIndex = 0; workflowIndex < workflows.size(); workflowIndex++) {
            AutomationWorkflow workflow = workflows.get(workflowIndex);
            String path = "automationWorkflows[" + workflowIndex + "]";
            registerResourceId(violations, resourceIds, workflow.id(), path + ".id");
            requireName(violations, workflow.name(), path + ".name");
            if (workflow.steps().isEmpty()) {
                violations.add(new ConfigViolation(path + ".steps", "workflow is empty"));
            }
            Map<String, String> stepIds = new HashMap<>();
            for (int stepIndex = 0; stepIndex < workflow.steps().size(); stepIndex++) {
                AutomationStep step = workflow.steps().get(stepIndex);
                String stepPath = path + ".steps[" + stepIndex + "]";
                requireUnique(violations, stepIds, step.id(), stepPath + ".id");
                requireSafeId(violations, step.id(), stepPath + ".id");
                validateAutomationStep(
                        violations,
                        step,
                        stepPath,
                        coordinates,
                        containers,
                        pickupsById,
                        config
                );
            }
        }
    }

    private static void validateTaskSettings(
            List<ConfigViolation> violations,
            Map<String, PickupRegion> regions,
            Map<String, NamedCoordinate> coordinates,
            Map<String, ContainerRef> containers,
            List<PickupWorkflow> pickups,
            List<AutomationWorkflow> automations,
            StowlingConfig config
    ) {
        Map<String, PickupWorkflow> pickupsById = new HashMap<>();
        for (PickupWorkflow pickup : pickups) {
            pickupsById.put(pickup.id(), pickup);
        }
        Map<String, TaskSettings> automationSettings = new HashMap<>();
        Map<String, String> automationPaths = new HashMap<>();
        List<TaskSettings> allSettings = new ArrayList<>();
        List<String> allPaths = new ArrayList<>();
        for (int index = 0; index < pickups.size(); index++) {
            PickupWorkflow pickup = pickups.get(index);
            String path = "pickupWorkflows[" + index + "].settings";
            allSettings.add(pickup.settings());
            allPaths.add(path);
            validateTaskSettings(
                    violations,
                    TaskKey.PICKUP,
                    pickup.settings(),
                    path,
                    regions,
                    coordinates,
                    containers,
                    pickupsById,
                    config
            );
        }
        for (int index = 0; index < automations.size(); index++) {
            AutomationWorkflow automation = automations.get(index);
            String path = "automationWorkflows[" + index + "].settings";
            automationSettings.put(automation.id(), automation.settings());
            automationPaths.put(automation.id(), path);
            allSettings.add(automation.settings());
            allPaths.add(path);
            validateTaskSettings(
                    violations,
                    TaskKey.AUTOMATION,
                    automation.settings(),
                    path,
                    regions,
                    coordinates,
                    containers,
                    pickupsById,
                    config
            );
        }
        validateTaskReferences(
                violations,
                allSettings,
                allPaths,
                automationSettings
        );
        validateInterruptionCycles(
                violations,
                automationSettings,
                automationPaths
        );
    }

    private static void validateTaskSettings(
            List<ConfigViolation> violations,
            String taskKind,
            TaskSettings settings,
            String path,
            Map<String, PickupRegion> regions,
            Map<String, NamedCoordinate> coordinates,
            Map<String, ContainerRef> containers,
            Map<String, PickupWorkflow> pickupsById,
            StowlingConfig config
    ) {
        validateTrigger(
                violations,
                taskKind,
                settings.trigger(),
                path + ".trigger",
                regions
        );
        validateInterruptionFields(
                violations,
                settings.interruption(),
                path + ".interruption",
                coordinates,
                containers,
                pickupsById,
                config
        );
    }

    private static void validateTrigger(
            List<ConfigViolation> violations,
            String taskKind,
            TaskTrigger trigger,
            String path,
            Map<String, PickupRegion> regions
    ) {
        TriggerKind kind = trigger.kind();
        validateZoneId(violations, trigger.zoneId(), path + ".zoneId");
        validateTimeValues(violations, trigger.times(), path + ".times");
        validateTriggerRegionReferences(
                violations,
                trigger.regionIds(),
                path + ".regionIds",
                regions
        );
        boolean intervalAllowed = kind == TriggerKind.FIXED_INTERVAL
                || kind == TriggerKind.AFTER_COMPLETION;
        if (!intervalAllowed && trigger.intervalSeconds() != 0L) {
            violations.add(new ConfigViolation(
                    path + ".intervalSeconds",
                    "interval is only valid for FIXED_INTERVAL or "
                            + "AFTER_COMPLETION triggers"
            ));
        }
        if (kind != TriggerKind.DAILY && !trigger.times().isEmpty()) {
            violations.add(new ConfigViolation(
                    path + ".times",
                    "times are only valid for DAILY triggers"
            ));
        }
        if (kind != TriggerKind.ITEMS_PRESENT && !trigger.regionIds().isEmpty()) {
            violations.add(new ConfigViolation(
                    path + ".regionIds",
                    "regions are only valid for ITEMS_PRESENT triggers"
            ));
        }
        switch (kind) {
            case FIXED_INTERVAL -> {
                if (trigger.intervalSeconds() <= 0L) {
                    violations.add(new ConfigViolation(
                            path + ".intervalSeconds",
                            "interval must be positive"
                    ));
                }
            }
            case DAILY -> {
                if (trigger.times().isEmpty()) {
                    violations.add(new ConfigViolation(
                            path + ".times",
                            "daily trigger requires at least one time"
                    ));
                }
            }
            case ITEMS_PRESENT -> {
                if (trigger.regionIds().isEmpty()
                        && !TaskKey.PICKUP.equals(taskKind)) {
                    violations.add(new ConfigViolation(
                            path + ".regionIds",
                            "items-present trigger requires at least one region"
                    ));
                }
            }
            case AFTER_COMPLETION -> {
                if (trigger.intervalSeconds() <= 0L) {
                    violations.add(new ConfigViolation(
                            path + ".intervalSeconds",
                            "after-completion interval must be positive"
                    ));
                }
            }
            case MANUAL -> {
                // Manual triggers have no additional parameters.
            }
            default -> throw new IllegalStateException("unsupported trigger kind");
        }
    }

    private static void validateZoneId(
            List<ConfigViolation> violations,
            String zoneId,
            String path
    ) {
        if (zoneId == null || zoneId.isBlank()) {
            violations.add(new ConfigViolation(path, "zone id must not be blank"));
            return;
        }
        try {
            ZoneId.of(zoneId);
        } catch (DateTimeException exception) {
            violations.add(new ConfigViolation(path, "unknown time zone: " + zoneId));
        }
    }

    private static void validateTimeValues(
            List<ConfigViolation> violations,
            List<String> times,
            String path
    ) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < times.size(); index++) {
            String value = times.get(index);
            String valuePath = path + "[" + index + "]";
            if (value == null || !TIME_OF_DAY.matcher(value).matches()) {
                violations.add(new ConfigViolation(
                        valuePath,
                        "time must use HH:mm"
                ));
            } else if (!seen.add(value)) {
                violations.add(new ConfigViolation(
                        valuePath,
                        "duplicate daily time"
                ));
            }
        }
    }

    private static void validateTriggerRegionReferences(
            List<ConfigViolation> violations,
            List<String> regionIds,
            String path,
            Map<String, PickupRegion> regions
    ) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < regionIds.size(); index++) {
            String regionId = regionIds.get(index);
            String regionPath = path + "[" + index + "]";
            requireSafeId(violations, regionId, regionPath);
            if (!seen.add(regionId)) {
                violations.add(new ConfigViolation(
                        regionPath,
                        "duplicate region reference"
                ));
            }
            if (!regions.containsKey(regionId)) {
                violations.add(new ConfigViolation(
                        regionPath,
                        "pickup region does not exist: " + regionId
                ));
            }
        }
    }

    private static void validateInterruptionFields(
            List<ConfigViolation> violations,
            TaskInterruption interruption,
            String path,
            Map<String, NamedCoordinate> coordinates,
            Map<String, ContainerRef> containers,
            Map<String, PickupWorkflow> pickupsById,
            StowlingConfig config
    ) {
        if (interruption.kind() != InterruptionKind.INLINE
                && !interruption.steps().isEmpty()) {
            violations.add(new ConfigViolation(
                    path + ".steps",
                    "steps are only valid for INLINE interruptions"
            ));
        }
        if (interruption.kind() != InterruptionKind.TASK_REFERENCE
                && interruption.taskId() != null) {
            violations.add(new ConfigViolation(
                    path + ".taskId",
                    "task id is only valid for TASK_REFERENCE interruptions"
            ));
        }
        switch (interruption.kind()) {
            case NONE -> {
                // No interruption work is configured.
            }
            case INLINE -> validateInlineSteps(
                    violations,
                    interruption.steps(),
                    path + ".steps",
                    coordinates,
                    containers,
                    pickupsById,
                    config
            );
            case TASK_REFERENCE -> {
                if (interruption.taskId() == null) {
                    violations.add(new ConfigViolation(
                            path + ".taskId",
                            "task reference interruption requires a task id"
                    ));
                }
            }
            default -> throw new IllegalStateException("unsupported interruption kind");
        }
    }

    private static void validateInlineSteps(
            List<ConfigViolation> violations,
            List<AutomationStep> steps,
            String path,
            Map<String, NamedCoordinate> coordinates,
            Map<String, ContainerRef> containers,
            Map<String, PickupWorkflow> pickupsById,
            StowlingConfig config
    ) {
        if (steps.isEmpty()) {
            violations.add(new ConfigViolation(
                    path,
                    "inline interruption requires at least one step"
            ));
            return;
        }
        Map<String, String> stepIds = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            AutomationStep step = steps.get(index);
            String stepPath = path + "[" + index + "]";
            requireUnique(violations, stepIds, step.id(), stepPath + ".id");
            requireSafeId(violations, step.id(), stepPath + ".id");
            validateAutomationStep(
                    violations,
                    step,
                    stepPath,
                    coordinates,
                    containers,
                    pickupsById,
                    config
            );
        }
    }

    private static void validateTaskReferences(
            List<ConfigViolation> violations,
            List<TaskSettings> allSettings,
            List<String> allPaths,
            Map<String, TaskSettings> automationSettings
    ) {
        for (int index = 0; index < allSettings.size(); index++) {
            validateTaskReference(
                    violations,
                    allSettings.get(index).interruption(),
                    allPaths.get(index) + ".interruption.taskId",
                    automationSettings
            );
        }
    }

    private static void validateTaskReference(
            List<ConfigViolation> violations,
            TaskInterruption interruption,
            String path,
            Map<String, TaskSettings> automationSettings
    ) {
        if (interruption.kind() != InterruptionKind.TASK_REFERENCE
                || interruption.taskId() == null) {
            return;
        }
        requireSafeId(violations, interruption.taskId(), path);
        if (!automationSettings.containsKey(interruption.taskId())) {
            violations.add(new ConfigViolation(
                    path,
                    "interruption task does not exist or is not an automation task: "
                            + interruption.taskId()
            ));
        }
    }

    private static void validateInterruptionCycles(
            List<ConfigViolation> violations,
            Map<String, TaskSettings> automationSettings,
            Map<String, String> automationPaths
    ) {
        Set<String> visited = new HashSet<>();
        List<String> stack = new ArrayList<>();
        for (String taskId : automationSettings.keySet()) {
            visitInterruptionTask(
                    taskId,
                    automationSettings,
                    automationPaths,
                    visited,
                    stack,
                    violations
            );
        }
    }

    private static void visitInterruptionTask(
            String taskId,
            Map<String, TaskSettings> automationSettings,
            Map<String, String> automationPaths,
            Set<String> visited,
            List<String> stack,
            List<ConfigViolation> violations
    ) {
        if (visited.contains(taskId)) {
            return;
        }
        stack.add(taskId);
        TaskInterruption interruption = automationSettings.get(taskId).interruption();
        if (interruption.kind() == InterruptionKind.TASK_REFERENCE
                && interruption.taskId() != null
                && automationSettings.containsKey(interruption.taskId())) {
            if (stack.contains(interruption.taskId())) {
                int start = stack.indexOf(interruption.taskId());
                List<String> cycle = new ArrayList<>(stack.subList(start, stack.size()));
                cycle.add(interruption.taskId());
                violations.add(new ConfigViolation(
                        automationPaths.get(taskId) + ".interruption.taskId",
                        "interruption task reference creates a cycle: "
                                + String.join(" -> ", cycle)
                ));
            } else {
                visitInterruptionTask(
                        interruption.taskId(),
                        automationSettings,
                        automationPaths,
                        visited,
                        stack,
                        violations
                );
            }
        }
        stack.remove(stack.size() - 1);
        visited.add(taskId);
    }

    private static void validateAutomationStep(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            Map<String, NamedCoordinate> coordinates,
            Map<String, ContainerRef> containers,
            Map<String, PickupWorkflow> pickupsById,
            StowlingConfig config
    ) {
        switch (step.type()) {
            case NAVIGATE -> {
                requireParameters(violations, step, path, Set.of("coordinateId"), Set.of());
                validateCoordinateParameter(
                        violations,
                        step,
                        path,
                        coordinates,
                        "coordinateId"
                );
            }
            case WAIT -> {
                requireParameters(violations, step, path, Set.of("seconds"), Set.of());
                validatePositiveNumber(violations, step, path, "seconds");
            }
            case RUN_PICKUP -> {
                requireParameters(
                        violations,
                        step,
                        path,
                        Set.of(),
                        Set.of(
                                "workflowId", "regionId", "coordinateId", "radius",
                                "waitForItems",
                                "standbyCoordinateId", "firstCornerCoordinateId",
                                "secondCornerCoordinateId", "cacheIds", "idleSeconds",
                                "classificationTiming"
                        )
                );
                validatePickupParameters(violations, step, path, pickupsById, config);
            }
            case PICKUP_TO_CONTAINER -> {
                requireParameters(
                        violations,
                        step,
                        path,
                        Set.of("coordinateId", "destinationCoordinateId"),
                        Set.of("containerType", "idleSeconds", "waitForItems")
                );
                validateCoordinateParameter(
                        violations,
                        step,
                        path,
                        coordinates,
                        "coordinateId"
                );
                validateCoordinateParameter(
                        violations,
                        step,
                        path,
                        coordinates,
                        "destinationCoordinateId"
                );
                validatePickupToContainerParameters(
                        violations,
                        step,
                        path,
                        coordinates
                );
            }
            case TAKE_ITEMS, PUT_ITEMS -> {
                requireParameters(violations, step, path, Set.of("itemId", "count"),
                        Set.of("containerId", "coordinateId", "containerType"));
                boolean explicit = step.parameters().containsKey("coordinateId");
                boolean legacy = step.parameters().containsKey("containerId");
                if (explicit == legacy || (!explicit
                        && step.parameters().containsKey("containerType"))) {
                    violations.add(new ConfigViolation(path + ".parameters",
                            "select coordinateId or legacy containerId, not both"));
                }
                if (explicit) {
                    validateTaskContainers(violations, step, path, config,
                            List.of("coordinateId"), List.of("containerType"));
                } else {
                    validateContainerParameter(violations, step, path, containers, "containerId");
                }
                validatePositiveNumber(violations, step, path, "count");
            }
            case TRANSFER_ITEMS -> {
                requireParameters(violations, step, path, Set.of("itemId", "count"), Set.of(
                        "sourceContainerId", "destination", "sourceCoordinateId",
                        "destinationCoordinateId", "sourceContainerType", "destinationContainerType"
                ));
                boolean explicit = step.parameters().containsKey("sourceCoordinateId")
                        || step.parameters().containsKey("destinationCoordinateId");
                boolean legacy = step.parameters().containsKey("sourceContainerId")
                        || step.parameters().containsKey("destination");
                if (explicit == legacy || (!explicit
                        && (step.parameters().containsKey("sourceContainerType")
                                || step.parameters().containsKey("destinationContainerType")))) {
                    violations.add(new ConfigViolation(path + ".parameters",
                            "do not mix coordinate and legacy container parameters"));
                }
                if (explicit) {
                    validateTaskContainers(violations, step, path, config,
                            List.of("sourceCoordinateId", "destinationCoordinateId"),
                            List.of("sourceContainerType", "destinationContainerType"));
                } else {
                    requireParameters(violations, step, path,
                            Set.of("sourceContainerId", "destination", "itemId", "count"),
                            Set.of());
                    validateContainerParameter(violations, step, path, containers,
                            "sourceContainerId");
                    String destination = step.parameter("destination");
                    if (destination != null && !"PLAYER".equals(destination)) {
                        validateContainerId(violations, path + ".parameters.destination",
                                destination, containers);
                    }
                }
                validatePositiveNumber(violations, step, path, "count");
            }
            case PACK_ITEMS -> {
                requireParameters(violations, step, path, Set.of(
                        "sourceCoordinateId", "boxCoordinateId", "outputCoordinateId",
                        "fallbackCoordinateId", "itemId", "count", "completionTrigger",
                        "completionThreshold"
                ), Set.of("sourceContainerType", "outputContainerType", "fallbackContainerType"));
                validateTaskContainers(violations, step, path, config,
                        List.of("sourceCoordinateId", "boxCoordinateId", "outputCoordinateId",
                                "fallbackCoordinateId"),
                        List.of("sourceContainerType", "", "outputContainerType",
                                "fallbackContainerType"));
                validatePositiveNumber(violations, step, path, "count");
                validatePositiveNumber(violations, step, path, "completionThreshold");
                String trigger = step.parameter("completionTrigger");
                if (!"ON_ITEM_COUNT".equals(trigger) && !"ON_OCCUPANCY".equals(trigger)) {
                    violations.add(new ConfigViolation(path + ".parameters.completionTrigger",
                            "must be ON_ITEM_COUNT or ON_OCCUPANCY"));
                }
                try {
                    long threshold = Long.parseLong(step.parameter("completionThreshold"));
                    if (threshold > ("ON_OCCUPANCY".equals(trigger) ? 100 : 1728)) {
                        violations.add(new ConfigViolation(path + ".parameters.completionThreshold",
                                "exceeds the packing limit"));
                    }
                } catch (NumberFormatException ignored) {
                    // Missing/invalid thresholds are reported by the checks above.
                }
            }
            case CHECK_INVENTORY, ACQUIRE_MATERIALS, CRAFT_ITEM -> {
                requireParameters(
                        violations,
                        step,
                        path,
                        Set.of("itemId", "count"),
                        Set.of()
                );
                validatePositiveNumber(violations, step, path, "count");
            }
            default -> throw new IllegalStateException("unsupported automation step");
        }
    }

    private static void validateTaskContainers(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            StowlingConfig config,
            List<String> coordinateKeys,
            List<String> typeKeys
    ) {
        List<ContainerRef> resolved = new ArrayList<>();
        for (int index = 0; index < coordinateKeys.size(); index++) {
            String key = coordinateKeys.get(index);
            String coordinateId = step.parameter(key);
            if (coordinateId == null) {
                violations.add(new ConfigViolation(path + ".parameters." + key,
                        "required coordinate is missing"));
                continue;
            }
            requireSafeId(violations, coordinateId, path + ".parameters." + key);
            try {
                resolved.add(TaskContainerParameters.container(() -> config, coordinateId,
                        TaskContainerParameters.containerType(step, typeKeys.get(index))));
            } catch (IllegalArgumentException exception) {
                violations.add(new ConfigViolation(path + ".parameters." + key,
                        exception.getMessage()));
            }
        }
        if (resolved.size() == coordinateKeys.size()) {
            try {
                TaskContainerParameters.requireSameDimension(resolved, "task");
            } catch (IllegalArgumentException exception) {
                violations.add(new ConfigViolation(path + ".parameters", exception.getMessage()));
            }
        }
    }

    private static void requireParameters(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            Set<String> required,
            Set<String> optional
    ) {
        for (String key : required) {
            if (!step.parameters().containsKey(key)) {
                violations.add(new ConfigViolation(
                        path + ".parameters." + key,
                        "required parameter is missing"
                ));
            }
        }
        for (String key : step.parameters().keySet()) {
            if (!required.contains(key) && !optional.contains(key)) {
                violations.add(new ConfigViolation(
                        path + ".parameters." + key,
                        "unknown parameter for " + step.type()
                ));
            }
        }
    }

    private static void validateCoordinateParameter(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            Map<String, NamedCoordinate> coordinates,
            String key
    ) {
        String id = step.parameter(key);
        if (id == null) {
            return;
        }
        String parameterPath = path + ".parameters." + key;
        requireSafeId(violations, id, parameterPath);
        if (!coordinates.containsKey(id)) {
            violations.add(new ConfigViolation(
                    parameterPath,
                    "coordinate does not exist: " + id
            ));
        }
    }

    private static void validateContainerParameter(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            Map<String, ContainerRef> containers,
            String key
    ) {
        String id = step.parameter(key);
        if (id != null) {
            validateContainerId(
                    violations,
                    path + ".parameters." + key,
                    id,
                    containers
            );
        }
    }

    private static void validateContainerId(
            List<ConfigViolation> violations,
            String path,
            String id,
            Map<String, ContainerRef> containers
    ) {
        requireSafeId(violations, id, path);
        if (!containers.containsKey(id)) {
            violations.add(new ConfigViolation(
                    path,
                    "container does not exist: " + id
            ));
        }
    }

    private static void validatePickupParameters(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            Map<String, PickupWorkflow> pickupsById,
            StowlingConfig config
    ) {
        validateBooleanParameter(violations, step, path, "waitForItems");
        try {
            Optional<CustomPickupRules> rules = CustomPickupRules.fromParameters(
                    step.parameters()
            );
            if (rules.isPresent()) {
                rules.orElseThrow().plan(new UUID(0L, 0L), config);
                return;
            }
        } catch (IllegalArgumentException exception) {
            violations.add(new ConfigViolation(path + ".parameters", exception.getMessage()));
            return;
        }
        requireParameters(violations, step, path, Set.of("workflowId"), Set.of(
                "regionId", "waitForItems"
        ));
        String workflowId = step.parameter("workflowId");
        if (workflowId == null) {
            return;
        }
        String workflowPath = path + ".parameters.workflowId";
        requireSafeId(violations, workflowId, workflowPath);
        PickupWorkflow pickupWorkflow = pickupsById.get(workflowId);
        if (pickupWorkflow == null) {
            violations.add(new ConfigViolation(
                    workflowPath,
                    "pickup workflow does not exist: " + workflowId
            ));
            return;
        }
        String regionId = step.parameter("regionId");
        if (regionId == null) {
            return;
        }
        String regionPath = path + ".parameters.regionId";
        requireSafeId(violations, regionId, regionPath);
        boolean exists = pickupWorkflow.regions().stream()
                .anyMatch(binding -> binding.regionId().equals(regionId));
        if (!exists) {
            violations.add(new ConfigViolation(
                    regionPath,
                    "pickup workflow does not contain region: " + regionId
            ));
        }
    }

    private static void validatePickupToContainerParameters(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            Map<String, NamedCoordinate> coordinates
    ) {
        NamedCoordinate pickup = coordinates.get(step.parameter("coordinateId"));
        NamedCoordinate destination = coordinates.get(
                step.parameter("destinationCoordinateId")
        );
        if (pickup != null && pickup.type() == CoordinateType.POINT) {
            violations.add(new ConfigViolation(
                    path + ".parameters.coordinateId",
                    "pickup range must be SPHERE, CUBE, or AREA"
            ));
        }
        if (destination != null && destination.type() != CoordinateType.POINT) {
            violations.add(new ConfigViolation(
                    path + ".parameters.destinationCoordinateId",
                    "destination must be a POINT coordinate"
            ));
        }
        if (pickup != null && destination != null
                && !pickup.dimension().equals(destination.dimension())) {
            violations.add(new ConfigViolation(
                    path + ".parameters",
                    "pickup and destination must share one dimension"
            ));
        }
        validateBooleanParameter(violations, step, path, "waitForItems");
        validateNonnegativeNumber(violations, step, path, "idleSeconds");
        String type = step.parameter("containerType");
        if (type != null) {
            try {
                TaskContainerParameters.parseContainerType("containerType", type);
            } catch (IllegalArgumentException exception) {
                violations.add(new ConfigViolation(
                        path + ".parameters.containerType",
                        exception.getMessage()
                ));
            }
        }
    }

    private static void validateBooleanParameter(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            String key
    ) {
        String value = step.parameter(key);
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (!"true".equals(normalized) && !"false".equals(normalized)) {
            violations.add(new ConfigViolation(
                    path + ".parameters." + key,
                    "must be true or false"
            ));
        }
    }

    private static void validatePositiveNumber(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            String key
    ) {
        String value = step.parameter(key);
        if (value == null) {
            return;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
        } catch (NumberFormatException exception) {
            violations.add(new ConfigViolation(
                    path + ".parameters." + key,
                    "must be a positive integer"
            ));
        }
    }

    private static void validateNonnegativeNumber(
            List<ConfigViolation> violations,
            AutomationStep step,
            String path,
            String key
    ) {
        String value = step.parameter(key);
        if (value == null) {
            return;
        }
        try {
            if (Long.parseLong(value) < 0) {
                throw new NumberFormatException("negative");
            }
        } catch (NumberFormatException exception) {
            violations.add(new ConfigViolation(
                    path + ".parameters." + key,
                    "must be a nonnegative integer"
            ));
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
