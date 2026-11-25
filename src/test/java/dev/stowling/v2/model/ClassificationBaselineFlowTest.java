package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationBaselineFlowTest {
    @Test
    void independentClassificationUsesTheMergedSingleSourceFlowWithoutExperimentalModules() {
        var definition = definition(AutoClassifyDefinition.Kind.CLASSIFICATION);
        var task = AutoClassifyTaskFactory.tasks(definition, List.of()).stream()
                .filter(value -> value.id().equals(definition.classificationTaskId()))
                .findFirst().orElseThrow();
        var steps = flatten(task.steps());
        assertEquals(2, steps.stream().filter(step -> ConfigValidator.WITHDRAW_ITEMS
                .equals(step.moduleId())).count());
        assertEquals(definition.classificationAreas().stream()
                        .map(AutoClassifyAreaDefinition::categoryZoneRef).toList(),
                steps.stream().filter(step -> ConfigValidator.RESOLVE_CONTAINER_CATEGORIES
                                .equals(step.moduleId()))
                        .map(step -> step.parameters().get(ConfigValidator.CATEGORY_ZONE_REF))
                        .toList());
        assertTrue(steps.stream().noneMatch(step -> step.moduleId().equals(
                "CLASSIFY_MULTI_SOURCE_BATCHES")
                || step.moduleId().equals("SUMMARIZE_CLASSIFICATION_BATCHES")));
        assertEquals(steps.size(), steps.stream().map(TaskStep::id).distinct().count());
        var assertions = steps.stream().filter(step -> ConfigValidator.ASSERT_NO_MANAGED_ITEMS
                .equals(step.moduleId())).toList();
        assertEquals("carried", assertions.getFirst().parameters().get(ConfigValidator.ITEM_SOURCE));
        assertTrue(assertions.getLast().parameters().isEmpty());
    }

    @Test
    void ordinarySortingDoesNotInheritTheIndependentClassificationHandoff() {
        var source = "region:" + UUID.randomUUID();
        var zone = "category-zone:" + UUID.randomUUID();
        var ordinary = flatten(TaskFlowTemplates.sortByCategoryZone(source, zone, Map.of()));
        var classification = flatten(TaskFlowTemplates.sortByCategoryZone(
                source, zone, Map.of(), List.of(), true));
        assertEquals(1, ordinary.stream().filter(step -> ConfigValidator.SELECT_STORAGE_TARGET
                .equals(step.moduleId())).count());
        assertEquals(2, classification.stream().filter(step -> ConfigValidator.SELECT_STORAGE_TARGET
                .equals(step.moduleId())).count());
        assertTrue(ordinary.stream().filter(step -> ConfigValidator.ASSERT_NO_MANAGED_ITEMS
                        .equals(step.moduleId())).allMatch(step -> step.parameters().isEmpty()));
    }

    @Test
    void generatedRunsAreStableWhenSavedStepsAreNormalizedAgain() {
        var definition = definition(AutoClassifyDefinition.Kind.CLASSIFICATION);
        var source = ResourceReference.parse(definition.cacheLocationRef()).uuid();
        var regions = List.of(new RegionDefinition(source, "Source", 0, 64, 0, 0, 64, 0));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                AutoClassifyTaskFactory.tasks(definition, List.of()), regions,
                List.of(), List.of(), List.of(), List.of(definition));
        var first = TaskFlowNormalizer.normalize(config);
        assertEquals(first, TaskFlowNormalizer.normalize(first));
        assertFalse(first.tasks().isEmpty());
    }

    private static AutoClassifyDefinition definition(AutoClassifyDefinition.Kind kind) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, false, true,
                kind == AutoClassifyDefinition.Kind.CLASSIFICATION ? null
                        : "coordinate:" + UUID.randomUUID(),
                null, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID()),
                        new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL, false, null, null, null, null, kind, null,
                null, null, null, null, null);
    }

    private static List<TaskStep> flatten(List<TaskStep> steps) {
        List<TaskStep> result = new ArrayList<>();
        for (TaskStep step : steps) {
            result.add(step);
            result.addAll(flatten(step.steps()));
            result.addAll(flatten(step.otherwise()));
        }
        return List.copyOf(result);
    }
}
