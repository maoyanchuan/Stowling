package dev.stowling.v2.model;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.AutoClassifyDefinition.Kind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AutoClassifyTaskFactory {
    private static final int TRIGGER_COOLDOWN_TICKS = 100;
    private static final int MAX_NAME_LENGTH = 80;

    private AutoClassifyTaskFactory() {
    }

    public static List<TaskDefinition> tasks(
            AutoClassifyDefinition definition,
            Collection<String> unavailableNames
    ) {
        Set<String> names = normalizedNames(unavailableNames);
        if (!names.add(ConfigValidator.normalizeName(definition.name()))) {
            throw new IllegalArgumentException("auto classify name is already used");
        }
        String classificationName = uniqueName(
                definition.name() + " 分类",
                names
        );
        String interruptionName = uniqueName(
                definition.name() + " 返缓存",
                names
        );
        String classificationRef = ResourceReference.of(
                ResourceType.TASK,
                definition.classificationTaskId()
        ).encode();
        String interruptionRef = ResourceReference.of(
                ResourceType.TASK,
                definition.interruptionTaskId()
        ).encode();
        Map<String, String> collectionParameters = new LinkedHashMap<>();
        if (definition.pickupRadius() == null) {
            collectionParameters.put(ConfigValidator.DEFAULT_PICKUP_RADIUS,
                    Integer.toString(AutoClassifyDefinition.DEFAULT_PICKUP_RADIUS));
        } else {
            collectionParameters.put(ConfigValidator.PICKUP_RADIUS,
                    Integer.toString(definition.pickupRadius()));
        }
        if (definition.cachePolicy().enabled()) {
            collectionParameters.put("cacheIntakeMode", definition.cachePolicy().mode().name());
            collectionParameters.put("cacheThresholdPercent",
                    Integer.toString(definition.cachePolicy().thresholdPercent()));
            collectionParameters.put("classificationTaskRef",
                    definition.classificationEnabled() ? classificationRef : "");
        }
        List<TaskStep> collectionSteps = definition.kind() == Kind.CLASSIFICATION
                ? new ArrayList<>() : new ArrayList<>(
                definition.kind() == Kind.PICKUP && !definition.cachePolicy().enabled()
                        ? TaskFlowTemplates.pickupToCache(definition.detectionCoordinateRef(),
                                definition.cacheLocationRef(), collectionParameters)
                        : TaskFlowTemplates.collect(
                        definition.detectionCoordinateRef(),
                        definition.cacheLocationRef(),
                        collectionParameters
                )
        );
        if (definition.kind() == Kind.COMBINED && definition.classificationEnabled()) {
            collectionSteps.add(TaskStep.createTask(
                    UUID.randomUUID(),
                    Map.of(
                            ConfigValidator.TASK_REF,
                            classificationRef,
                            ConfigValidator.PRIORITY,
                            TaskPriority.BACKGROUND.name()
                    )
            ));
        }
        if (definition.followUpEnabled() && definition.followUpTaskRef() != null) {
            collectionSteps.add(TaskStep.createTask(UUID.randomUUID(),
                    Map.of(ConfigValidator.TASK_REF, definition.followUpTaskRef(),
                            ConfigValidator.CREATE_AFTER_SUCCESS, "true",
                            ConfigValidator.CREATE_DELAY_SECONDS,
                            Integer.toString(definition.followUpDelaySeconds()))));
        }
        List<TaskStep> classificationSteps = new ArrayList<>();
        for (AutoClassifyAreaDefinition area : definition.classificationAreas()) {
            Map<String, String> scan = new LinkedHashMap<>();
            scan.put(ConfigValidator.ALLOW_EMPTY_CONTAINER_SCAN, "true");
            List<TaskStep> preResolveSteps = List.of(TaskFlowTemplates.refreshMarkerContainers(
                    UUID.randomUUID(), area.categoryZoneRef()));
            classificationSteps.addAll(
                    TaskFlowTemplates.sortByCategoryZone(
                            definition.cacheLocationRef(),
                            area.categoryZoneRef(),
                            scan,
                            preResolveSteps
                    )
            );
        }
        var remainders = definition.remainderPolicy();
        if (remainders.unclassifiableEnabled()) {
            classificationSteps.add(routeRemainder(definition.cacheLocationRef(),
                    remainders.unclassifiableDestinationRef(),
                    ClassificationRemainderPolicy.UNCLASSIFIABLE));
        }
        if (remainders.overflowEnabled()) {
            classificationSteps.add(routeRemainder(definition.cacheLocationRef(),
                    remainders.overflowDestinationRef(), ClassificationRemainderPolicy.OVERFLOW));
        }
        if (definition.overflowEnabled()) {
            classificationSteps.add(TaskFlowTemplates.overflow(definition.cacheLocationRef(),
                    definition.overflowDestinationRef(), definition.robotWarehouseMixedBoxes()));
        }
        classificationSteps.addAll(TaskFlowTemplates.reconcileInventory(
                definition.cacheLocationRef(), Map.of()
        ));
        var boxWarehouse = definition.cleanupPolicy().emptyBoxWarehouse();
        if (boxWarehouse != null && boxWarehouse.enabled()) {
            classificationSteps.add(TaskStep.action(UUID.randomUUID(),
                    EmptyBoxWarehousePolicy.SETTLE_MODULE, Map.of()));
        }
        List<TaskDefinition> result = new ArrayList<>();
        if (definition.kind() != Kind.CLASSIFICATION) {
            result.add(new TaskDefinition(definition.id(), definition.name(),
                    definition.collectionPriority(), false, collectionSteps,
                    TaskInterruptionMode.CUSTOM, interruptionRef, false, false, null,
                    definition.cleanupPolicy()));
        }
        if (definition.kind() != Kind.PICKUP) {
            boolean combined = definition.kind() == Kind.COMBINED;
            result.add(new TaskDefinition(definition.classificationTaskId(),
                    combined ? classificationName : definition.name(), TaskPriority.BACKGROUND,
                    false, classificationSteps, TaskInterruptionMode.CUSTOM, interruptionRef,
                    false, combined, null, definition.cleanupPolicy()));
        }
        result.add(new TaskDefinition(definition.interruptionTaskId(), interruptionName,
                TaskPriority.NORMAL, false, TaskFlowTemplates.interruption(
                        definition.cacheLocationRef()), TaskInterruptionMode.NONE, null,
                false, false, null, definition.cleanupPolicy()));
        return List.copyOf(result);
    }

    private static TaskStep routeRemainder(String source, String destination, String kind) {
        return TaskStep.action(UUID.randomUUID(), ClassificationRemainderPolicy.MODULE,
                Map.of(ConfigValidator.CACHE_LOCATION_REF, source,
                        ConfigValidator.TARGET_REF, destination,
                        ClassificationRemainderPolicy.KIND, kind));
    }

    public static List<TaskTriggerDefinition> triggers(
            AutoClassifyDefinition definition,
            Collection<String> unavailableNames
    ) {
        if (!definition.enabled()) {
            return List.of();
        }
        Set<String> names = normalizedNames(unavailableNames);
        List<TaskTriggerDefinition> triggers = new ArrayList<>();
        if (definition.collectionEnabled()) {
            String collectionName = uniqueName(
                    definition.name() + " 掉落监听",
                    names
            );
            ResourceReference coordinate = ResourceReference.parse(
                    definition.detectionCoordinateRef()
            );
            triggers.add(
                    new TaskTriggerDefinition(
                            definition.collectionTriggerId(),
                            collectionName,
                            TaskTriggerType.DROPPED_ITEMS,
                            definition.id(),
                            definition.collectionPriority(),
                            definition.detectionIntervalSeconds() * 20,
                            TRIGGER_COOLDOWN_TICKS,
                            coordinate.uuid(),
                            null,
                            null,
                            null,
                            definition.pickupRadius()
                    )
            );
        }
        return List.copyOf(triggers);
    }

    public static Set<UUID> ownedTaskIds(
            Collection<AutoClassifyDefinition> definitions
    ) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (AutoClassifyDefinition definition : definitions) {
            ids.add(definition.id());
            if (definition.kind() == Kind.COMBINED) ids.add(definition.classificationTaskId());
            ids.add(definition.interruptionTaskId());
        }
        return Set.copyOf(ids);
    }

    public static Set<UUID> ownedTriggerIds(
            Collection<AutoClassifyDefinition> definitions
    ) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (AutoClassifyDefinition definition : definitions) {
            ids.add(definition.collectionTriggerId());
            ids.add(definition.classificationTriggerId());
        }
        return Set.copyOf(ids);
    }

    public static String uniqueName(
            String candidate,
            Collection<String> names
    ) {
        return uniqueName(candidate, normalizedNames(names));
    }

    private static String uniqueName(String candidate, Set<String> names) {
        String base = candidate.length() > MAX_NAME_LENGTH
                ? candidate.substring(0, MAX_NAME_LENGTH)
                : candidate;
        String result = base;
        int suffix = 2;
        while (names.contains(ConfigValidator.normalizeName(result))) {
            String marker = " " + suffix++;
            int prefixLength = Math.min(
                    base.length(),
                    MAX_NAME_LENGTH - marker.length()
            );
            result = base.substring(0, prefixLength) + marker;
        }
        names.add(ConfigValidator.normalizeName(result));
        return result;
    }

    private static Set<String> normalizedNames(Collection<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            result.add(ConfigValidator.normalizeName(name));
        }
        return result;
    }
}
