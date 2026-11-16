package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskFlowNormalizerTest {
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
    void expandsLegacySortWithoutZoneAndCreatesEditableCleanup() {
        UUID regionId = UUID.randomUUID();
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
                        ConfigValidator.SCAN_SOURCE,
                        "previous-scan"
                )
        ));
        CategoryZoneDefinition unrelatedZone = new CategoryZoneDefinition(
                UUID.randomUUID(),
                "Unrelated zone",
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
                List.of(unrelatedZone)
        );

        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        TaskDefinition parent = normalized.tasks().get(0);

        assertEquals(2, normalized.tasks().size());
        assertEquals(TaskInterruptionMode.CUSTOM, parent.interruptionMode());
        assertTrue(parent.interruptionTaskRef().startsWith("task:"));
        assertEquals(ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                parent.steps().get(0).moduleId());
        assertEquals(ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                parent.steps().get(1).moduleId());
        TaskStep cacheLoop = parent.steps().get(2).steps().get(1);
        assertEquals(ConfigValidator.MOVE_TO_COORDINATE,
                cacheLoop.steps().getFirst().moduleId());
        assertEquals(ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                parent.steps().getLast().moduleId());
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
