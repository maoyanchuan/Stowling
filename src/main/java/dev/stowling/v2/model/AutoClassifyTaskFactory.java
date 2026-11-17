package dev.stowling.v2.model;

import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AutoClassifyTaskFactory {
    private static final int TRIGGER_INTERVAL_TICKS = 20;
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
        List<TaskStep> collectionSteps = new ArrayList<>(
                TaskFlowTemplates.collect(
                        definition.detectionCoordinateRef(),
                        definition.cacheRegionRef(),
                        Map.of(
                                ConfigValidator.PICKUP_RADIUS,
                                Integer.toString(definition.pickupRadius())
                        )
                )
        );
        if (definition.classificationEnabled()) {
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
        List<TaskStep> classificationSteps = new ArrayList<>();
        for (AutoClassifyAreaDefinition area : definition.classificationAreas()) {
            String markerSources = markerSources(
                    ContainerContentsCategoryRule.ID.equals(area.ruleId())
                            ? List.of(CategoryMarkerSource.CONTAINER_CONTENTS)
                            : CategoryMarkerSource.SMART_SOURCES
            );
            Map<String, String> scan = new LinkedHashMap<>();
            scan.put(ConfigValidator.MARKER_SOURCES, markerSources);
            scan.put(ConfigValidator.CATEGORY_RULE_ID, area.ruleId());
            scan.put(ConfigValidator.ALLOW_EMPTY_CONTAINER_SCAN, "true");
            List<TaskStep> preResolveSteps = List.of();
            if (ContainerContentsCategoryRule.ID.equals(area.ruleId())
                    && area.markerSources().contains(
                            CategoryMarkerSource.CONTAINER_CONTENTS
                    )) {
                preResolveSteps = TaskFlowTemplates.refreshMarkers(
                        area.categoryZoneRef(),
                        null,
                        markerSources,
                        null,
                        area.ruleId()
                );
            }
            classificationSteps.addAll(
                    TaskFlowTemplates.sortByCategoryZone(
                            definition.cacheRegionRef(),
                            area.categoryZoneRef(),
                            scan,
                            preResolveSteps
                    )
            );
        }
        classificationSteps.addAll(TaskFlowTemplates.reconcileInventory(
                definition.cacheRegionRef(), Map.of()
        ));
        return List.of(
                new TaskDefinition(
                        definition.id(),
                        definition.name(),
                        definition.collectionPriority(),
                        false,
                        collectionSteps,
                        TaskInterruptionMode.CUSTOM,
                        interruptionRef,
                        false,
                        false,
                        null
                ),
                new TaskDefinition(
                        definition.classificationTaskId(),
                        classificationName,
                        TaskPriority.BACKGROUND,
                        false,
                        classificationSteps,
                        TaskInterruptionMode.CUSTOM,
                        interruptionRef,
                        false,
                        true,
                        null
                ),
                new TaskDefinition(
                        definition.interruptionTaskId(),
                        interruptionName,
                        TaskPriority.NORMAL,
                        false,
                        TaskFlowTemplates.interruption(
                                definition.cacheRegionRef()
                        ),
                        TaskInterruptionMode.NONE,
                        null,
                        false,
                        false,
                        null
                )
        );
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
                            TRIGGER_INTERVAL_TICKS,
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
            ids.add(definition.classificationTaskId());
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

    public static String markerSources(
            Collection<CategoryMarkerSource> sources
    ) {
        return sources.stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(","));
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
