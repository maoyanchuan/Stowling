package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskFlowNormalizerTest {
    @Test
    void everySelectedRefreshAreaPreventsResourceDeletion() {
        var first = new ResourceReference(ResourceType.REGION, UUID.randomUUID().toString());
        var second = new ResourceReference(ResourceType.CATEGORY_ZONE, UUID.randomUUID().toString());
        var definition = task(TaskStep.action(UUID.randomUUID(), ContainerCacheRefresh.MODULE,
                Map.of("targetRefs", new com.google.gson.Gson().toJson(List.of(first.encode(),
                        second.encode())), "foodPauseResumeEnabled", "true")));
        assertTrue(TaskFlowNormalizer.references(definition, first));
        assertTrue(TaskFlowNormalizer.references(definition, second));
    }

    @Test
    void refreshBaselineMigrationCleansNestedStepsAndPreservesOtherModules() {
        for (String hand : List.of("true", "false", "invalid", "")) {
            for (String route : List.of("true", "false", "invalid", "")) {
                var values = Map.of("targetRef", "region:" + UUID.randomUUID(),
                        "categoryStorageOnlyEnabled", "true", "chests", "false",
                        "reuseSafeHandEnabled", hand, "nearbyRouteEnabled", route);
                var refresh = TaskStep.action(UUID.randomUUID(), ContainerCacheRefresh.MODULE,
                        values);
                var other = TaskStep.action(UUID.randomUUID(), "OTHER_MODULE", values);
                var branch = TaskStep.conditional(UUID.randomUUID(),
                        Map.of("condition", "ALWAYS"), List.of(refresh), List.of(refresh));
                var original = task(refresh, branch, other);
                var normalized = TaskFlowNormalizer.normalize(config(original)).tasks().getFirst();
                var expected = Map.of("targetRef", values.get("targetRef"),
                        "categoryStorageOnlyEnabled", "true", "chests", "false");
                assertEquals(original.id(), normalized.id());
                assertEquals(refresh.id(), normalized.steps().getFirst().id());
                assertEquals(expected, normalized.steps().getFirst().parameters());
                assertEquals(expected, normalized.steps().get(1).steps().getFirst().parameters());
                assertEquals(expected,
                        normalized.steps().get(1).otherwise().getFirst().parameters());
                assertEquals(values, normalized.steps().getLast().parameters());
                assertEquals(normalized, TaskFlowNormalizer.normalize(config(normalized))
                        .tasks().getFirst());
            }
        }
    }

    @Test
    void expandsLegacyAutoEatIntoConditionalFlow() {
        UUID coordinate = UUID.randomUUID();
        TaskDefinition task = task(new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.AUTO_EAT_AT_COORDINATE,
                Map.of(
                        ConfigValidator.COORDINATE_ID,
                        coordinate.toString(),
                        ConfigValidator.FOOD_ITEM_ID,
                        "minecraft:bread",
                        ConfigValidator.FOOD_LEVEL_THRESHOLD,
                        "0"
                )
        ));

        TaskDefinition normalized = TaskFlowNormalizer.normalize(config(task))
                .tasks().getFirst();

        assertEquals(TaskStepKind.IF, normalized.steps().getFirst().kind());
        assertEquals(
                "coordinate:" + coordinate,
                normalized.steps().getFirst().steps().getFirst()
                        .parameters().get(ConfigValidator.TARGET_REF)
        );
    }

    @Test
    void preservesManualMoveCoordinates() {
        TaskDefinition task = task(TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.X, "-12",
                        ConfigValidator.Y, "64",
                        ConfigValidator.Z, "30"
                )
        ));

        TaskStep normalized = TaskFlowNormalizer.normalize(config(task))
                .tasks().getFirst().steps().getFirst();

        assertEquals(
                Map.of(
                        ConfigValidator.X, "-12",
                        ConfigValidator.Y, "64",
                        ConfigValidator.Z, "30"
                ),
                normalized.parameters()
        );
    }

    @Test
    void expandsLegacyDepositIntoSelectMoveDepositLoop() {
        UUID region = UUID.randomUUID();
        TaskDefinition task = task(new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.REGION_ID,
                        region.toString(),
                        ConfigValidator.SCAN_SOURCE,
                        "previous-scan",
                        ConfigValidator.ITEM_SOURCE,
                        "collected-this-run",
                        ConfigValidator.FILL_MODE,
                        "nearest-first"
                )
        ));

        List<TaskStep> steps = TaskFlowNormalizer.normalize(config(task))
                .tasks().getFirst().steps();

        assertEquals(ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                steps.get(0).moduleId());
        assertEquals(TaskStepKind.REPEAT, steps.get(1).kind());
        assertEquals(ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                steps.get(2).moduleId());
        List<TaskStep> loop = steps.get(1).steps();
        assertEquals(ConfigValidator.SELECT_CONTAINER, loop.get(0).moduleId());
        assertEquals(ConfigValidator.MOVE_TO_COORDINATE, loop.get(1).moduleId());
        assertEquals(ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                loop.get(2).moduleId());
        assertEquals("collected",
                loop.get(2).parameters().get(ConfigValidator.ITEM_SOURCE));
    }

    @Test
    void expandsLegacySortWithExplicitZoneAndCreatesEditableCleanup() {
        UUID regionId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        RegionDefinition region = new RegionDefinition(
                regionId,
                "Cache",
                0,
                64,
                0,
                8,
                70,
                8
        );
        TaskDefinition task = task(new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.SORT_CACHE_ITEMS,
                Map.of(
                        ConfigValidator.CACHE_REGION_ID,
                        regionId.toString(),
                        ConfigValidator.CATEGORY_ZONE_ID,
                        zoneId.toString(),
                        ConfigValidator.SCAN_SOURCE,
                        "previous-scan"
                )
        ));
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                zoneId,
                "Zone",
                List.of(new CategoryBindingDefinition(
                        UUID.randomUUID(),
                        new AreaBounds(100, 64, 100, 104, 64, 100),
                        new AreaBounds(100, 64, 98, 104, 64, 98),
                        "DIRECT_BLOCK_ID"
                ))
        );
        AppConfig config = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(task),
                List.of(region),
                List.of(zone)
        );

        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        TaskDefinition parent = normalized.tasks().get(0);

        assertEquals(2, normalized.tasks().size());
        assertEquals(TaskInterruptionMode.CUSTOM, parent.interruptionMode());
        assertTrue(parent.interruptionTaskRef().startsWith("task:"));
        assertEquals(ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                parent.steps().get(0).moduleId());
        assertEquals(TaskStepKind.IF, parent.steps().get(1).kind());
        assertEquals(ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                parent.steps().get(1).steps().get(0).moduleId());
        TaskStep cacheLoop = parent.steps().get(1).steps().get(1);
        assertEquals(TaskStepKind.REPEAT, cacheLoop.kind());
        assertEquals(ConfigValidator.SELECT_CONTAINER,
                cacheLoop.steps().getFirst().moduleId());
        assertEquals(ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                normalized.tasks().get(1).steps().getLast().moduleId());
        AppConfig normalizedAgain = TaskFlowNormalizer.normalize(normalized);
        assertEquals(2, normalizedAgain.tasks().size());
        assertEquals(
                parent.interruptionTaskRef(),
                normalizedAgain.tasks().getFirst().interruptionTaskRef()
        );
        AppConfig missingCleanup = new AppConfig(
                normalized.schemaVersion(),
                normalized.coordinates(),
                List.of(parent),
                normalized.regions(),
                normalized.categoryZones(),
                normalized.triggers()
        );
        assertEquals(
                2,
                TaskFlowNormalizer.normalize(missingCleanup).tasks().size()
        );
    }

    @Test
    void rejectsLegacySortWithoutExplicitCategoryZone() {
        RegionDefinition region = new RegionDefinition(
                UUID.randomUUID(),
                "Cache",
                0,
                64,
                0,
                8,
                70,
                8
        );
        TaskDefinition task = task(new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.SORT_CACHE_ITEMS,
                Map.of(
                        ConfigValidator.CACHE_REGION_ID,
                        region.id().toString(),
                        ConfigValidator.SCAN_SOURCE,
                        "previous-scan"
                )
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> TaskFlowNormalizer.normalize(new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        List.of(),
                        List.of(task),
                        List.of(region)
                ))
        );
    }

    @Test
    void keepsFoldersAndAssignmentsForGeneratedLegacyCleanup() {
        UUID regionId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        TaskFolderDefinition folder = new TaskFolderDefinition(
                folderId,
                "Legacy"
        );
        RegionDefinition region = new RegionDefinition(
                regionId,
                "Cache",
                0,
                64,
                0,
                8,
                70,
                8
        );
        TaskDefinition task = new TaskDefinition(
                UUID.randomUUID(),
                "Legacy sort",
                TaskPriority.NORMAL,
                false,
                List.of(new TaskStep(
                        UUID.randomUUID(),
                        ConfigValidator.SORT_CACHE_ITEMS,
                        Map.of(
                                ConfigValidator.CACHE_REGION_ID,
                                regionId.toString(),
                                ConfigValidator.CATEGORY_ZONE_ID,
                                zoneId.toString()
                        )
                )),
                TaskInterruptionMode.DEFAULT,
                null,
                false,
                false,
                folderId
        );
        AppConfig config = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(task),
                List.of(region),
                List.of(new CategoryZoneDefinition(
                        zoneId,
                        "Zone",
                        List.of(new CategoryBindingDefinition(
                                UUID.randomUUID(),
                                new AreaBounds(20, 64, 20, 24, 64, 24),
                                new AreaBounds(20, 64, 18, 24, 64, 18),
                                "DIRECT_BLOCK_ID"
                        ))
                )),
                List.of(),
                List.of(folder)
        );

        AppConfig normalized = TaskFlowNormalizer.normalize(config);

        assertEquals(List.of(folder), normalized.taskFolders());
        assertEquals(2, normalized.tasks().size());
        assertEquals(folderId, normalized.tasks().getFirst().folderId());
        assertEquals(folderId, normalized.tasks().getLast().folderId());
    }

    @Test
    void keepsNewClassificationTaskOnDefaultInterruption() {
        UUID regionId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        TaskDefinition task = task(TaskFlowTemplates.sortByCategoryZone(
                "region:" + regionId,
                "category-zone:" + zoneId,
                Map.of()
        ));

        TaskDefinition normalized = TaskFlowNormalizer.normalize(config(task))
                .tasks().getFirst();

        assertEquals(TaskInterruptionMode.DEFAULT, normalized.interruptionMode());
        assertEquals(null, normalized.interruptionTaskRef());
    }

    private static TaskDefinition task(TaskStep... steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Task-" + UUID.randomUUID(),
                List.of(steps)
        );
    }

    private static TaskDefinition task(List<TaskStep> steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Task-" + UUID.randomUUID(),
                steps
        );
    }

    private static AppConfig config(TaskDefinition task) {
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(task)
        );
    }
}
