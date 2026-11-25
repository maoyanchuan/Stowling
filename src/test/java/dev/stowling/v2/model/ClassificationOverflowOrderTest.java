package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationOverflowOrderTest {
    @Test
    void defaultAndLegacyPoliciesKeepTheSingleExistingBaseline() {
        var legacy = new Gson().fromJson("{}", ClassificationRemainderPolicy.class);
        assertFalse(legacy.robotWarehouseFirstEnabled());
        assertFalse(ClassificationRemainderPolicy.DEFAULT.robotWarehouseFirstEnabled());
        assertFalse(new ClassificationRemainderPolicy(false, null, false, null)
                .robotWarehouseFirstEnabled());
    }

    @Test
    void bothFeatureSwitchesAndTheDebugSwitchControlOneOrderedStageList() {
        for (boolean first : List.of(false, true)) {
            for (boolean total : List.of(false, true)) {
                for (boolean robot : List.of(false, true)) {
                    var definition = definition(AutoClassifyDefinition.Kind.CLASSIFICATION,
                            first, total, robot);
                    var steps = AutoClassifyTaskFactory.tasks(definition, List.of())
                            .getFirst().steps();
                    var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                            AutoClassifyTaskFactory.tasks(definition, List.of()), List.of(),
                            List.of(), List.of(), List.of(), List.of(definition));
                    var normalized = TaskFlowNormalizer.normalize(config).tasks().getFirst().steps();
                    assertEquals(steps.stream().map(TaskStep::moduleId).toList(),
                            normalized.stream().map(TaskStep::moduleId).toList());
                    assertEquals(TaskCleanupRouting.fromSteps(steps),
                            TaskCleanupRouting.fromSteps(normalized));
                    var routing = steps.stream().filter(step -> ClassificationRemainderPolicy.MODULE
                                    .equals(step.moduleId()) || ConfigValidator.ROBOT_WAREHOUSE
                                    .equals(step.moduleId()))
                            .map(step -> step.parameters().getOrDefault(
                                    ClassificationRemainderPolicy.KIND, step.moduleId())).toList();
                    var expected = new java.util.ArrayList<>(List.of("UNCLASSIFIABLE"));
                    if (robot && first) expected.add(ConfigValidator.ROBOT_WAREHOUSE);
                    if (total) expected.add("OVERFLOW");
                    if (robot && !first) expected.add(ConfigValidator.ROBOT_WAREHOUSE);
                    assertEquals(expected, routing);
                    var cleanup = TaskCleanupRouting.fromSteps(steps);
                    assertEquals(first && total && robot, cleanup.robotWarehouseFirstEnabled());
                    assertEquals(cleanup, TaskCleanupRouting.fromParameters(cleanup.parameters()));
                    assertEquals(definition.remainderPolicy(), new Gson().fromJson(
                            new Gson().toJson(definition.remainderPolicy()),
                            ClassificationRemainderPolicy.class));
                }
            }
        }
    }

    @Test
    void combinedTasksAndOrdinaryCleanupDoNotInheritTheDebugOrder() {
        var definition = definition(AutoClassifyDefinition.Kind.COMBINED, true, true, true);
        var steps = AutoClassifyTaskFactory.tasks(definition, List.of()).get(1).steps();
        var cleanup = TaskCleanupRouting.fromSteps(steps);
        assertFalse(cleanup.robotWarehouseFirstEnabled());
        assertFalse(new TaskCleanupRouting(true, null, null, true).robotWarehouseFirstEnabled());
        assertTrue(steps.stream().filter(step -> ConfigValidator.ROBOT_WAREHOUSE
                        .equals(step.moduleId())).allMatch(step -> !step.parameters()
                        .containsKey(ClassificationRemainderPolicy.ROBOT_FIRST)));
        var pickup = definition(AutoClassifyDefinition.Kind.PICKUP, true, true, true);
        assertTrue(AutoClassifyTaskFactory.tasks(pickup, List.of()).stream()
                .flatMap(task -> task.steps().stream()).allMatch(step -> !step.parameters()
                        .containsKey(ClassificationRemainderPolicy.ROBOT_FIRST)));
    }

    private static AutoClassifyDefinition definition(AutoClassifyDefinition.Kind kind,
            boolean first, boolean total, boolean robot) {
        var remainder = new ClassificationRemainderPolicy(true, ref("coordinate"), total,
                ref("region"), first);
        return new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, true, true,
                ref("coordinate"), 8, ref("region"),
                List.of(new AutoClassifyAreaDefinition(ref("category-zone"))),
                TaskPriority.NORMAL, robot, null, null, ref("region"), null, kind,
                null, null, null, false, remainder, false);
    }

    private static String ref(String type) {
        return type + ":" + UUID.randomUUID();
    }
}
