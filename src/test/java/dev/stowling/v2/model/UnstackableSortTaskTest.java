package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UnstackableSortTaskTest {
    private final RegionDefinition cache = region("Cache", 0);
    private final RegionDefinition storage = region("Unstackable", 10);

    @Test
    void compactTaskCompilesToInventoryScanAndCompleteTransferFlow() {
        TaskDefinition task = task(sort(reference(cache), reference(storage)));
        TaskDefinition compiled = TaskInteractionCompiler.compile(task, List.of());
        List<TaskStep> steps = flatten(compiled.steps()).toList();
        List<String> modules = steps.stream().map(TaskStep::moduleId).toList();

        assertEquals(task.id(), compiled.id());
        assertEquals(ConfigValidator.SCAN_CONTAINERS_IN_REGION, steps.getFirst().moduleId());
        assertEquals(Map.of(ConfigValidator.REGION_REF, reference(cache),
                ConfigValidator.SCAN_CONTENTS, "true"), steps.getFirst().parameters());
        TaskStep resolve = steps.stream()
                .filter(step -> step.moduleId().equals(ConfigValidator.RESOLVE_UNSTACKABLE_STORAGE))
                .findFirst().orElseThrow();
        assertEquals(reference(storage), resolve.parameters().get(ConfigValidator.REGION_REF));
        assertTrue(modules.containsAll(List.of(ConfigValidator.SELECT_CONTAINER,
                ConfigValidator.MOVE_TO_COORDINATE, ConfigValidator.WITHDRAW_ITEMS,
                ConfigValidator.SELECT_STORAGE_TARGET, ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS)));
        assertFalse(modules.contains(ConfigValidator.SORT_UNSTACKABLE_ITEMS));
        assertFalse(modules.contains(ConfigValidator.SCAN_CATEGORY_MARKERS));
        TaskStep target = steps.stream()
                .filter(step -> step.moduleId().equals(ConfigValidator.SELECT_STORAGE_TARGET))
                .findFirst().orElseThrow();
        assertEquals("true", target.parameters().get(ConfigValidator.ALLOW_CACHE_FALLBACK));
    }

    @Test
    void compilesSortInsideBothConditionalBranches() {
        TaskStep sort = sort(reference(cache), reference(storage));
        TaskStep conditional = TaskStep.conditional(UUID.randomUUID(),
                Map.of("condition", "ALWAYS"), List.of(sort),
                List.of(sort(reference(cache), reference(storage))));
        TaskStep compiled = TaskInteractionCompiler.compile(task(conditional), List.of())
                .steps().getFirst();

        assertEquals(2, compiled.steps().size());
        assertEquals(2, compiled.otherwise().size());
        assertFalse(flatten(List.of(compiled))
                .anyMatch(step -> step.moduleId().equals(ConfigValidator.SORT_UNSTACKABLE_ITEMS)));
    }

    @Test
    void savesStandaloneTaskWithoutCoordinatesOrCategoryMarkers() {
        AppConfig config = config(sort(reference(cache), reference(storage)));

        assertDoesNotThrow(() -> ConfigValidator.requireValid(config));
        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        assertEquals(config.tasks().getFirst().steps(), normalized.tasks().getFirst().steps());
        assertDoesNotThrow(() -> ConfigValidator.requireValid(normalized));
    }

    @Test
    void rejectsMissingRegionsWrongReferenceTypesAndTheSameRegion() {
        for (TaskStep invalid : List.of(
                sort(reference(cache), reference(cache)),
                sort(reference(cache), "region:" + UUID.randomUUID()),
                sort(reference(cache), "coordinate:" + UUID.randomUUID()),
                TaskStep.action(UUID.randomUUID(), ConfigValidator.SORT_UNSTACKABLE_ITEMS,
                        Map.of(ConfigValidator.REGION_REF, reference(cache))),
                TaskStep.action(UUID.randomUUID(), ConfigValidator.SORT_UNSTACKABLE_ITEMS,
                        Map.of(ConfigValidator.REGION_REF, reference(cache),
                                ConfigValidator.TARGET_REF, reference(storage), "itemFilter", "*"))
        )) {
            assertThrows(V2ValidationException.class,
                    () -> ConfigValidator.requireValid(config(invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> TaskInteractionCompiler.compile(
                task(sort(reference(cache), reference(cache))), List.of()));
        assertThrows(IllegalArgumentException.class, () -> TaskInteractionCompiler.compile(
                task(sort(reference(cache), "output:container")), List.of()));
    }

    private AppConfig config(TaskStep step) {
        return new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), List.of(task(step)),
                List.of(cache, storage), List.of());
    }

    private static TaskDefinition task(TaskStep step) {
        return new TaskDefinition(UUID.randomUUID(), "不可堆叠分类", List.of(step));
    }

    private static TaskStep sort(String source, String target) {
        return TaskStep.action(UUID.randomUUID(), ConfigValidator.SORT_UNSTACKABLE_ITEMS,
                Map.of(ConfigValidator.REGION_REF, source, ConfigValidator.TARGET_REF, target));
    }

    private static String reference(RegionDefinition region) {
        return ResourceReference.of(ResourceType.REGION, region.id()).encode();
    }

    private static RegionDefinition region(String name, int x) {
        return new RegionDefinition(UUID.randomUUID(), name, x, 64, 0, x + 1, 65, 1);
    }

    private static Stream<TaskStep> flatten(List<TaskStep> steps) {
        return steps.stream().flatMap(step -> Stream.concat(Stream.of(step),
                Stream.concat(flatten(step.steps()), flatten(step.otherwise()))));
    }
}
