package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoClassifyTaskFactoryTest {
    @Test
    void upgradesPersistedCollectionWithoutReplacingItsHandoffOrFolder() {
        AutoClassifyDefinition definition = definition(true);
        List<TaskDefinition> tasks = new ArrayList<>(
                AutoClassifyTaskFactory.tasks(definition, List.of())
        );
        TaskDefinition collection = tasks.getFirst();
        List<TaskStep> oldSteps = List.of(
                collection.steps().get(0),
                collection.steps().get(1),
                collection.steps().get(2),
                collection.steps().get(collection.steps().size() - 3),
                collection.steps().getLast()
        );
        UUID folderId = UUID.randomUUID();
        TaskDefinition legacy = new TaskDefinition(
                collection.id(),
                collection.name(),
                collection.priority(),
                false,
                oldSteps,
                collection.interruptionMode(),
                collection.interruptionTaskRef(),
                false,
                false,
                folderId
        );
        tasks.set(0, legacy);
        AppConfig config = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                tasks,
                List.of(),
                List.of(),
                List.of(),
                List.of(new TaskFolderDefinition(folderId, "Items")),
                List.of(definition)
        );

        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        TaskDefinition upgraded = normalized.tasks().getFirst();

        assertEquals(8, upgraded.steps().size());
        assertEquals(oldSteps.subList(0, 3), upgraded.steps().subList(0, 3));
        assertEquals(ConfigValidator.SYNC_COLLECTED_INVENTORY,
                upgraded.steps().get(3).moduleId());
        assertEquals(TaskStepKind.REPEAT, upgraded.steps().get(4).kind());
        assertEquals(ConfigValidator.ASSERT_PLAYER_INVENTORY_RESTORED,
                upgraded.steps().get(6).moduleId());
        assertEquals(oldSteps.getLast(), upgraded.steps().getLast());
        assertEquals(folderId, upgraded.folderId());
        assertEquals(collection.interruptionTaskRef(), upgraded.interruptionTaskRef());
        assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
        AppConfig standalone = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks
        );
        assertEquals(oldSteps, TaskFlowNormalizer.normalize(standalone)
                .tasks().getFirst().steps());
    }

    @Test
    void buildsTwoIndependentStagesAndSharedInterruptionCleanup() {
        AutoClassifyDefinition definition = definition(false);

        List<TaskDefinition> tasks = AutoClassifyTaskFactory.tasks(
                definition,
                List.of()
        );

        assertEquals(3, tasks.size());
        TaskDefinition collection = tasks.get(0);
        TaskDefinition classification = tasks.get(1);
        TaskDefinition cleanup = tasks.get(2);
        assertEquals(definition.id(), collection.id());
        assertEquals(
                definition.classificationTaskId(),
                classification.id()
        );
        assertEquals(TaskPriority.BACKGROUND, classification.priority());
        assertTrue(classification.requeueAfterInterruption());
        assertEquals(false, collection.requeueAfterInterruption());
        assertEquals(
                collection.interruptionTaskRef(),
                classification.interruptionTaskRef()
        );
        assertEquals(
                cleanup.id(),
                ResourceReference.parse(
                        collection.interruptionTaskRef()
                ).uuid()
        );
        TaskStep createTask = collection.steps().getLast();
        assertEquals(TaskStepKind.CREATE_TASK, createTask.kind());
        assertEquals(
                "task:" + classification.id(),
                createTask.parameters().get(ConfigValidator.TASK_REF)
        );
        assertEquals(
                TaskPriority.BACKGROUND.name(),
                createTask.parameters().get(ConfigValidator.PRIORITY)
        );
        assertEquals(
                ConfigValidator.ASSERT_PLAYER_INVENTORY_RESTORED,
                collection.steps().get(collection.steps().size() - 2)
                        .moduleId()
        );
        TaskStep latePickupLoop = collection.steps().get(
                collection.steps().size() - 4
        );
        assertEquals(TaskStepKind.REPEAT, latePickupLoop.kind());
        assertEquals(
                TaskCondition.HAS_MANAGED_ITEMS.name(),
                latePickupLoop.parameters().get(TaskFlowTemplates.CONDITION)
        );
        assertEquals(
                ConfigValidator.SYNC_COLLECTED_INVENTORY,
                collection.steps().get(collection.steps().size() - 5)
                        .moduleId()
        );
        assertEquals(
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                classification.steps().getFirst().moduleId()
        );
        assertEquals(TaskStepKind.IF, classification.steps().get(1).kind());
        assertEquals(
                ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                classification.steps().get(1).steps().getFirst().moduleId()
        );
    }

    @Test
    void interruptionDepositLoopStopsOnceManagedItemsAreGone() {
        TaskStep depositLoop = TaskFlowTemplates.interruption(
                "region:" + UUID.randomUUID()
        ).get(1);

        assertEquals(TaskStepKind.REPEAT, depositLoop.kind());
        assertEquals(
                TaskCondition.HAS_MANAGED_ITEMS.name(),
                depositLoop.parameters().get(TaskFlowTemplates.CONDITION)
        );
    }

    @Test
    void allSourcesScanCacheBeforeRefreshingMarkersInsideHasContainersBranch() {
        AutoClassifyDefinition definition = definition(
                false,
                true,
                true,
                List.of(
                        CategoryMarkerSource.PLACED_ITEM,
                        CategoryMarkerSource.ITEM_FRAME,
                        CategoryMarkerSource.SHELF,
                        CategoryMarkerSource.FLOWER_POT,
                        CategoryMarkerSource.CONTAINER_CONTENTS
                ),
                "MARKER_CONTAINER_CONTENTS"
        );

        TaskDefinition classification = AutoClassifyTaskFactory.tasks(
                definition,
                List.of()
        ).get(1);

        assertEquals(2, classification.steps().size());
        TaskStep cacheScan = classification.steps().getFirst();
        assertEquals(
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                cacheScan.moduleId()
        );
        assertEquals(
                definition.cacheRegionRef(),
                cacheScan.parameters().get(ConfigValidator.REGION_REF)
        );
        TaskStep hasContainers = classification.steps().get(1);
        assertEquals(TaskStepKind.IF, hasContainers.kind());
        assertEquals(
                TaskCondition.HAS_CONTAINERS.name(),
                hasContainers.parameters().get(TaskFlowTemplates.CONDITION)
        );
        assertTrue(hasContainers.otherwise().isEmpty());
        List<TaskStep> classify = hasContainers.steps();
        TaskStep markerScan = classify.getFirst();
        assertEquals(ConfigValidator.SCAN_CATEGORY_MARKERS, markerScan.moduleId());
        assertEquals(
                definition.classificationAreas().getFirst().categoryZoneRef(),
                markerScan.parameters().get(ConfigValidator.CATEGORY_ZONE_REF)
        );
        assertEquals(TaskStepKind.REPEAT, classify.get(1).kind());
        assertEquals(
                ConfigValidator.SAVE_CATEGORY_MARKER_CACHE,
                classify.get(2).moduleId()
        );
        TaskStep resolve = classify.get(3);
        assertEquals(ConfigValidator.RESOLVE_CONTAINER_CATEGORIES, resolve.moduleId());
        assertEquals(
                markerScan.parameters().get(ConfigValidator.MARKER_SOURCES),
                resolve.parameters().get(ConfigValidator.MARKER_SOURCES)
        );
    }

    @Test
    void disabledDefinitionHasNoTriggers() {
        assertTrue(AutoClassifyTaskFactory.triggers(
                definition(false),
                List.of()
        ).isEmpty());
    }

    @Test
    void enabledDefinitionOnlyStartsTheDropListener() {
        AutoClassifyDefinition definition = definition(true);

        List<TaskTriggerDefinition> triggers =
                AutoClassifyTaskFactory.triggers(definition, List.of());

        assertEquals(1, triggers.size());
        TaskTriggerDefinition collection = triggers.get(0);
        assertEquals(TaskTriggerType.DROPPED_ITEMS, collection.type());
        assertEquals(definition.id(), collection.targetTaskId());
        assertEquals(16, collection.radius());
    }

    @Test
    void classificationIsQueuedByCollectionInsteadOfACacheTrigger() {
        List<TaskTriggerDefinition> bothPhases =
                AutoClassifyTaskFactory.triggers(
                        definition(true, true, true),
                        List.of()
                );
        List<TaskTriggerDefinition> classificationOnly =
                AutoClassifyTaskFactory.triggers(
                        definition(true, false, true),
                        List.of()
                );

        assertEquals(1, bothPhases.size());
        assertEquals(
                TaskTriggerType.DROPPED_ITEMS,
                bothPhases.getFirst().type()
        );
        assertTrue(classificationOnly.isEmpty());
    }

    @Test
    void disabledClassificationPhaseSkipsFollowUpQueueStep() {
        TaskDefinition collection = AutoClassifyTaskFactory.tasks(
                definition(false, true, false),
                List.of()
        ).getFirst();

        assertTrue(collection.steps().stream().noneMatch(
                step -> step.kind() == TaskStepKind.CREATE_TASK
        ));
    }

    @Test
    void buildsAnIndependentClassificationFlowForEachSelectedZone() {
        String firstZoneRef = "category-zone:" + UUID.randomUUID();
        String secondZoneRef = "category-zone:" + UUID.randomUUID();
        AutoClassifyDefinition definition = definition(List.of(
                new AutoClassifyAreaDefinition(
                        firstZoneRef,
                        "DIRECT_BLOCK_ID",
                        List.of(CategoryMarkerSource.PLACED_ITEM)
                ),
                new AutoClassifyAreaDefinition(
                        secondZoneRef,
                        "MARKER_CONTAINER_CONTENTS",
                        List.of(CategoryMarkerSource.CONTAINER_CONTENTS)
                )
        ));

        TaskDefinition classification = AutoClassifyTaskFactory.tasks(
                definition,
                List.of()
        ).get(1);

        assertEquals(4, classification.steps().size());
        TaskStep firstResolve = classification.steps().get(1).steps().stream()
                .filter(step -> step.moduleId().equals(
                        ConfigValidator.RESOLVE_CONTAINER_CATEGORIES
                ))
                .findFirst()
                .orElseThrow();
        TaskStep secondResolve = classification.steps().get(3).steps().stream()
                .filter(step -> step.moduleId().equals(
                        ConfigValidator.RESOLVE_CONTAINER_CATEGORIES
                ))
                .findFirst()
                .orElseThrow();
        assertEquals(
                firstZoneRef,
                firstResolve.parameters().get(
                        ConfigValidator.CATEGORY_ZONE_REF
                )
        );
        assertEquals(
                "DIRECT_BLOCK_ID",
                firstResolve.parameters().get(
                        ConfigValidator.CATEGORY_RULE_ID
                )
        );
        assertEquals(
                secondZoneRef,
                secondResolve.parameters().get(
                        ConfigValidator.CATEGORY_ZONE_REF
                )
        );
        assertEquals(
                "MARKER_CONTAINER_CONTENTS",
                secondResolve.parameters().get(
                        ConfigValidator.CATEGORY_RULE_ID
                )
        );
        assertEquals(
                "PLACED_ITEM,ITEM_FRAME,SHELF,FLOWER_POT",
                firstResolve.parameters().get(ConfigValidator.MARKER_SOURCES)
        );
    }

    private static AutoClassifyDefinition definition(boolean enabled) {
        return definition(enabled, List.of(CategoryMarkerSource.PLACED_ITEM));
    }

    private static AutoClassifyDefinition definition(
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled
    ) {
        return definition(
                enabled,
                collectionEnabled,
                classificationEnabled,
                List.of(CategoryMarkerSource.PLACED_ITEM)
        );
    }

    private static AutoClassifyDefinition definition(
            boolean enabled,
            List<CategoryMarkerSource> markerSources
    ) {
        return definition(
                enabled,
                true,
                true,
                markerSources,
                "DIRECT_BLOCK_ID"
        );
    }

    private static AutoClassifyDefinition definition(
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            List<CategoryMarkerSource> markerSources
    ) {
        return definition(
                enabled,
                collectionEnabled,
                classificationEnabled,
                markerSources,
                "DIRECT_BLOCK_ID"
        );
    }

    private static AutoClassifyDefinition definition(
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled,
            List<CategoryMarkerSource> markerSources,
            String ruleId
    ) {
        String zoneRef = "category-zone:" + UUID.randomUUID();
        return definition(
                List.of(new AutoClassifyAreaDefinition(
                        zoneRef,
                        ruleId,
                        markerSources
                )),
                enabled,
                collectionEnabled,
                classificationEnabled
        );
    }

    private static AutoClassifyDefinition definition(
            List<AutoClassifyAreaDefinition> areas
    ) {
        return definition(areas, true, true, true);
    }

    private static AutoClassifyDefinition definition(
            List<AutoClassifyAreaDefinition> areas,
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled
    ) {
        return new AutoClassifyDefinition(
                UUID.randomUUID(),
                "自动分类",
                enabled,
                collectionEnabled,
                classificationEnabled,
                "coordinate:" + UUID.randomUUID(),
                16,
                "region:" + UUID.randomUUID(),
                areas,
                TaskPriority.NORMAL
        );
    }
}
