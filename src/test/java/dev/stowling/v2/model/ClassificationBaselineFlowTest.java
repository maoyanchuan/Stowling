package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationBaselineFlowTest {
    @Test
    void optInReplacesOnlyTheClassificationCycleAndPreservesTheZoneOrder() {
        var definition = definition(AutoClassifyDefinition.Kind.CLASSIFICATION, true);
        var steps = AutoClassifyTaskFactory.tasks(definition, List.of()).getFirst().steps();
        var flattened = flatten(steps);
        assertEquals(2, flattened.stream()
                .filter(step -> ClassificationBaseline.MODULE.equals(step.moduleId())).count());
        assertEquals(definition.classificationAreas().stream()
                        .map(AutoClassifyAreaDefinition::categoryZoneRef).toList(),
                flattened.stream().filter(step -> ConfigValidator.RESOLVE_CONTAINER_CATEGORIES
                                .equals(step.moduleId()))
                        .map(step -> step.parameters().get(ConfigValidator.CATEGORY_ZONE_REF))
                        .toList());
        assertFalse(flattened.stream()
                .anyMatch(step -> ConfigValidator.WITHDRAW_ITEMS.equals(step.moduleId())));
        assertEquals(ClassificationBaseline.SUMMARY, steps.getLast().moduleId());
    }

    @Test
    void disabledCycleLeavesTheEstablishedTemplateInPlace() {
        var definition = definition(AutoClassifyDefinition.Kind.CLASSIFICATION, false);
        var steps = flatten(AutoClassifyTaskFactory.tasks(definition, List.of())
                .getFirst().steps());
        assertTrue(steps.stream()
                .anyMatch(step -> ConfigValidator.WITHDRAW_ITEMS.equals(step.moduleId())));
        assertFalse(steps.stream()
                .anyMatch(step -> ClassificationBaseline.MODULE.equals(step.moduleId())));
        assertTrue(definition.sameCategoryBoxBatching());
    }

    @Test
    void pickupAndCombinedTasksCannotInheritTheNewClassificationStrategy() {
        for (var kind : List.of(AutoClassifyDefinition.Kind.PICKUP,
                AutoClassifyDefinition.Kind.COMBINED)) {
            var definition = definition(kind, true);
            assertFalse(definition.classificationBaselineEnabled());
            var steps = AutoClassifyTaskFactory.tasks(definition, List.of()).stream()
                    .flatMap(task -> flatten(task.steps()).stream()).toList();
            assertFalse(steps.stream()
                    .anyMatch(step -> ClassificationBaseline.MODULE.equals(step.moduleId())));
        }
    }

    private static AutoClassifyDefinition definition(AutoClassifyDefinition.Kind kind,
            boolean baseline) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, true, true,
                "coordinate:" + UUID.randomUUID(), 8, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID()),
                        new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL, false, null, null, null, null, kind, null,
                null, null, null, null, null, true, baseline);
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
