package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoClassifyTaskFactoryTest {
    @Test
    void migratesUnstackableOnlyTaskToWarehouseLayout() {
        var point = new CoordinatePoint(UUID.randomUUID(), "Drops", 0, 64, 0);
        var cache = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 1, 64, 1);
        var storage = new RegionDefinition(UUID.randomUUID(), "Tools", 10, 64, 0, 12, 65, 1);
        String storageRef = "region:" + storage.id();
        var legacy = new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, true, true,
                "coordinate:" + point.id(), 8, "region:" + cache.id(), List.of(),
                TaskPriority.NORMAL, true, null, storageRef);
        List<TaskDefinition> oldTasks = new ArrayList<>(
                AutoClassifyTaskFactory.tasks(legacy, List.of()));
        TaskDefinition classification = oldTasks.get(1);
        oldTasks.set(1, new TaskDefinition(classification.id(), classification.name(),
                classification.priority(), false,
                TaskFlowTemplates.sortUnstackable(legacy.cacheRegionRef(), storageRef),
                classification.interruptionMode(), classification.interruptionTaskRef(),
                false, true));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point), oldTasks,
                List.of(cache, storage), List.of(), List.of(), List.of(), List.of(legacy));

        AppConfig migrated = TaskFlowNormalizer.normalize(config);
        var updated = migrated.autoClassifyTasks().getFirst();
        var layout = migrated.categoryZones().getFirst();
        var binding = layout.bindings().getFirst();
        String layoutRef = "category-zone:" + layout.id();
        List<TaskStep> steps = migrated.tasks().get(1).steps();

        assertEquals(legacy.id(), updated.id());
        assertNull(updated.unstackableRegionRef());
        assertEquals(List.of(new AutoClassifyAreaDefinition(layoutRef)),
                updated.classificationAreas());
        assertEquals(UnstackableCategoryRule.ID, binding.ruleId());
        assertEquals(new AreaBounds(10, 64, 0, 12, 65, 1), binding.storageArea());
        var resolve = steps.get(1).steps().get(3);
        assertEquals(ConfigValidator.RESOLVE_CONTAINER_CATEGORIES, resolve.moduleId());
        assertEquals(layoutRef, resolve.parameters().get(ConfigValidator.CATEGORY_ZONE_REF));
        assertEquals(ConfigValidator.ROBOT_WAREHOUSE, steps.get(2).moduleId());
        ConfigValidator.requireValid(migrated);
        assertEquals(migrated, TaskFlowNormalizer.normalize(migrated));

        var existing = new CategoryZoneDefinition(UUID.randomUUID(), "Existing tools layout",
                List.of(binding));
        var withExisting = new AppConfig(config.schemaVersion(), config.coordinates(), oldTasks,
                config.regions(), List.of(existing), config.triggers(), config.taskFolders(),
                config.autoClassifyTasks());
        var reused = TaskFlowNormalizer.normalize(withExisting);
        assertEquals(List.of(existing), reused.categoryZones());
        assertEquals("category-zone:" + existing.id(), reused.autoClassifyTasks().getFirst()
                .classificationAreas().getFirst().categoryZoneRef());

        var ordinaryBinding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(10, 64, -1, 12, 64, -1), binding.storageArea(), "DIRECT_BLOCK_ID");
        var ordinaryZone = new CategoryZoneDefinition(UUID.randomUUID(), "Regular stock",
                List.of(ordinaryBinding));
        var withOverlap = new AppConfig(config.schemaVersion(), config.coordinates(), oldTasks,
                config.regions(), List.of(ordinaryZone), config.triggers(), config.taskFolders(),
                config.autoClassifyTasks());
        var migratedOverlap = TaskFlowNormalizer.normalize(withOverlap);
        assertEquals(List.of(ordinaryZone, layout), migratedOverlap.categoryZones());
        ConfigValidator.requireValid(migratedOverlap);
    }

    @Test
    void overflowIsOptInAndRunsAfterEveryClassificationAreaButBeforeReconciliation() {
        AutoClassifyDefinition original = definition(true);
        assertFalse(original.overflowEnabled());
        var enabled = new AutoClassifyDefinition(original.id(), original.name(), original.enabled(),
                original.collectionEnabled(), original.classificationEnabled(),
                original.detectionCoordinateRef(), original.pickupRadius(), original.cacheRegionRef(),
                original.classificationAreas(), original.collectionPriority(), true);
        List<TaskDefinition> tasks = AutoClassifyTaskFactory.tasks(enabled, List.of());
        List<TaskStep> collect = tasks.getFirst().steps();
        List<TaskStep> batch = collect.get(1).steps();
        assertEquals(ConfigValidator.ROBOT_WAREHOUSE, batch.get(2).moduleId());
        assertTrue(batch.get(2).parameters().isEmpty());
        assertEquals(ConfigValidator.ASSERT_NO_MANAGED_ITEMS, batch.get(3).moduleId());
        assertEquals(ConfigValidator.COLLECT_DROPPED_ITEMS, batch.getLast().moduleId());
        assertEquals(ConfigValidator.SYNC_PLAYER_INVENTORY, collect.get(2).moduleId());
        List<TaskStep> latePickups = collect.get(3).steps();
        assertEquals(ConfigValidator.ROBOT_WAREHOUSE, latePickups.get(2).moduleId());
        assertTrue(latePickups.get(2).parameters().isEmpty());
        List<TaskStep> classify = tasks.get(1).steps();
        int index = original.classificationAreas().size() * 2;
        assertEquals(ConfigValidator.ROBOT_WAREHOUSE, classify.get(index).moduleId());
        assertEquals(original.cacheRegionRef(), classify.get(index).parameters()
                .get(ConfigValidator.REGION_REF));
        assertEquals(ConfigValidator.SYNC_PLAYER_INVENTORY, classify.get(index + 1).moduleId());
        assertTrue(AutoClassifyTaskFactory.tasks(original, List.of()).stream()
                .flatMap(task -> task.steps().stream())
                .noneMatch(step -> ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())));
        AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks,
                List.of(), List.of(), List.of(), List.of(), List.of(enabled));
        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        assertEquals(tasks, normalized.tasks());
        assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
    }

    @Test
    void upgradesNestedAutomaticOverflowAndPreservesStandaloneOverflow() {
        AutoClassifyDefinition definition = definition(true);
        TaskStep oldOverflow = TaskStep.action(UUID.randomUUID(), ConfigValidator.PACK_OVERFLOW,
                Map.of(ConfigValidator.REGION_REF, definition.cacheRegionRef()));
        TaskStep loop = TaskStep.repeat(UUID.randomUUID(), Map.of(), List.of(oldOverflow));
        var automatic = new TaskDefinition(definition.id(), "Auto", List.of(loop));
        var standalone = new TaskDefinition(UUID.randomUUID(), "Custom", List.of(oldOverflow));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                List.of(automatic, standalone), List.of(), List.of(), List.of(), List.of(),
                List.of(definition));

        var normalized = TaskFlowNormalizer.normalize(config);
        var upgraded = normalized.tasks().getFirst().steps().getFirst().steps().getFirst();

        assertEquals(ConfigValidator.ROBOT_WAREHOUSE, upgraded.moduleId());
        assertEquals(oldOverflow.id(), upgraded.id());
        assertEquals(oldOverflow.parameters(), upgraded.parameters());
        assertEquals(oldOverflow, normalized.tasks().getLast().steps().getFirst());
        assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
    }

    @Test
    void upgradesOldClassificationWithGlobalCleanupWithoutChangingSortingSteps() {
        AutoClassifyDefinition definition = definition(true);
        List<TaskDefinition> tasks = new ArrayList<>(
                AutoClassifyTaskFactory.tasks(definition, List.of())
        );
        TaskDefinition classification = tasks.get(1);
        List<TaskStep> sorting = classification.steps().subList(
                0, classification.steps().size() - 4
        );
        tasks.set(1, new TaskDefinition(classification.id(), classification.name(),
                classification.priority(), false, sorting, classification.interruptionMode(),
                classification.interruptionTaskRef(), false, true));
        AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks,
                List.of(), List.of(), List.of(), List.of(), List.of(definition));

        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        TaskDefinition upgraded = normalized.tasks().get(1);

        assertEquals(sorting, upgraded.steps().subList(0, sorting.size()));
        assertEquals(ConfigValidator.SYNC_PLAYER_INVENTORY,
                upgraded.steps().get(sorting.size()).moduleId());
        assertEquals(ConfigValidator.ASSERT_PLAYER_INVENTORY_RESTORED,
                upgraded.steps().getLast().moduleId());
        assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
    }

    @Test
    void upgradesPersistedCollectionWithoutReplacingItsHandoffOrFolder() {
        AutoClassifyDefinition definition = definition(true);
        List<TaskDefinition> tasks = new ArrayList<>(
                AutoClassifyTaskFactory.tasks(definition, List.of())
        );
        TaskDefinition collection = tasks.getFirst();
        List<TaskStep> batch = collection.steps().get(1).steps();
        List<TaskStep> oldSteps = List.of(
                collection.steps().get(0),
                batch.get(0),
                batch.get(1),
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

        assertEquals(7, upgraded.steps().size());
        assertEquals(oldSteps.getFirst(), upgraded.steps().getFirst());
        TaskStep batches = upgraded.steps().get(1);
        assertEquals(TaskStepKind.REPEAT, batches.kind());
        assertEquals(oldSteps.get(1).parameters(), batches.steps().get(0).parameters());
        assertEquals(oldSteps.get(2).parameters(), batches.steps().get(1).parameters());
        assertEquals(ConfigValidator.COLLECT_DROPPED_ITEMS, batches.steps().getLast().moduleId());
        assertEquals(ConfigValidator.SYNC_PLAYER_INVENTORY,
                upgraded.steps().get(2).moduleId());
        assertEquals(TaskStepKind.REPEAT, upgraded.steps().get(3).kind());
        assertEquals(ConfigValidator.ASSERT_PLAYER_INVENTORY_RESTORED,
                upgraded.steps().get(5).moduleId());
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
    void upgradesCurrentFlatCollectionAndKeepsItsExistingReconciliationAndHandoff() {
        AutoClassifyDefinition definition = definition(true);
        List<TaskDefinition> tasks = new ArrayList<>(
                AutoClassifyTaskFactory.tasks(definition, List.of()));
        TaskDefinition collection = tasks.getFirst();
        List<TaskStep> oldSteps = new ArrayList<>(List.of(collection.steps().getFirst()));
        oldSteps.addAll(collection.steps().get(1).steps().subList(0, 2));
        oldSteps.addAll(collection.steps().subList(2, collection.steps().size()));
        tasks.set(0, new TaskDefinition(collection.id(), collection.name(), collection.priority(),
                false, oldSteps, collection.interruptionMode(), collection.interruptionTaskRef(),
                false, false, collection.folderId(), collection.cleanupPolicy()));
        AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks,
                List.of(), List.of(), List.of(), List.of(), List.of(definition));

        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        List<TaskStep> upgraded = normalized.tasks().getFirst().steps();

        assertEquals(oldSteps.getFirst(), upgraded.getFirst());
        assertEquals(oldSteps.subList(3, oldSteps.size()), upgraded.subList(2, upgraded.size()));
        assertEquals("collected", upgraded.get(1).parameters().get(ConfigValidator.ITEM_SOURCE));
        assertEquals(ConfigValidator.COLLECT_DROPPED_ITEMS,
                upgraded.get(1).steps().getLast().moduleId());
        assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
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
                ConfigValidator.SYNC_PLAYER_INVENTORY,
                collection.steps().get(collection.steps().size() - 5)
                        .moduleId()
        );
        assertEquals(
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                classification.steps().getFirst().moduleId()
        );
        assertEquals(TaskStepKind.IF, classification.steps().get(1).kind());
        assertEquals(
                ConfigValidator.SCAN_CATEGORY_MARKERS,
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
    void scansCacheBeforeRefreshingLayoutMarkersInsideHasContainersBranch() {
        AutoClassifyDefinition definition = definition(false);

        TaskDefinition classification = AutoClassifyTaskFactory.tasks(
                definition,
                List.of()
        ).get(1);

        assertEquals(6, classification.steps().size());
        assertEquals(ConfigValidator.SYNC_PLAYER_INVENTORY,
                classification.steps().get(2).moduleId());
        TaskStep cacheScan = classification.steps().getFirst();
        assertEquals(
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                cacheScan.moduleId()
        );
        assertEquals(
                definition.cacheRegionRef(),
                cacheScan.parameters().get(ConfigValidator.REGION_REF)
        );
        assertEquals("true", cacheScan.parameters().get(ConfigValidator.SCAN_CONTENTS));
        TaskDefinition collection = AutoClassifyTaskFactory.tasks(definition, List.of()).getFirst();
        assertFalse(collection.steps().get(1).parameters()
                .containsKey(ConfigValidator.SCAN_CONTENTS));
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
        assertFalse(markerScan.parameters().containsKey(ConfigValidator.MARKER_SOURCES));
        assertFalse(markerScan.parameters().containsKey(ConfigValidator.CATEGORY_RULE_ID));
        assertFalse(resolve.parameters().containsKey(ConfigValidator.MARKER_SOURCES));
        assertFalse(resolve.parameters().containsKey(ConfigValidator.CATEGORY_RULE_ID));
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
                new AutoClassifyAreaDefinition(firstZoneRef),
                new AutoClassifyAreaDefinition(secondZoneRef)
        ));

        TaskDefinition classification = AutoClassifyTaskFactory.tasks(
                definition,
                List.of()
        ).get(1);

        assertEquals(8, classification.steps().size());
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
        assertFalse(firstResolve.parameters().containsKey(ConfigValidator.CATEGORY_RULE_ID));
        assertEquals(
                secondZoneRef,
                secondResolve.parameters().get(
                        ConfigValidator.CATEGORY_ZONE_REF
                )
        );
        assertFalse(secondResolve.parameters().containsKey(ConfigValidator.CATEGORY_RULE_ID));
        assertFalse(firstResolve.parameters().containsKey(ConfigValidator.MARKER_SOURCES));
        assertFalse(secondResolve.parameters().containsKey(ConfigValidator.MARKER_SOURCES));
    }

    private static AutoClassifyDefinition definition(boolean enabled) {
        return definition(enabled, true, true);
    }

    private static AutoClassifyDefinition definition(
            boolean enabled,
            boolean collectionEnabled,
            boolean classificationEnabled
    ) {
        String zoneRef = "category-zone:" + UUID.randomUUID();
        return definition(
                List.of(new AutoClassifyAreaDefinition(zoneRef)),
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
