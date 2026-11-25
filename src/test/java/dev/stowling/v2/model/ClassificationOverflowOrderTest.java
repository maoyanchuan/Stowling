package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationOverflowOrderTest {
    @Test
    void retiredDebugValuesDoNotCreateAlternativePolicies() {
        var gson = new Gson();
        for (boolean retired : List.of(false, true)) {
            var legacy = gson.fromJson("{\"robotWarehouseFirstEnabled\":" + retired + "}",
                    ClassificationRemainderPolicy.class);
            assertEquals(ClassificationRemainderPolicy.DEFAULT, legacy);
            assertFalse(gson.toJson(legacy).contains("robotWarehouseFirstEnabled"));
        }
    }

    @Test
    void classificationUsesTheWarehouseFirstBaselineForEveryFeatureCombination() {
        for (var kind : List.of(AutoClassifyDefinition.Kind.CLASSIFICATION,
                AutoClassifyDefinition.Kind.COMBINED)) {
            for (boolean total : List.of(false, true)) {
                for (boolean robot : List.of(false, true)) {
                    var definition = definition(kind, total, robot);
                    var tasks = AutoClassifyTaskFactory.tasks(definition, List.of());
                    var steps = classification(tasks, definition).steps();
                    var expected = new ArrayList<>(List.of("UNCLASSIFIABLE"));
                    if (robot) expected.add(ConfigValidator.ROBOT_WAREHOUSE);
                    if (total) expected.add("OVERFLOW");
                    assertEquals(expected, routing(steps));
                    var normalized = classification(TaskFlowNormalizer.normalize(
                            config(definition, tasks)).tasks(), definition).steps();
                    assertEquals(steps, normalized);
                    var cleanup = TaskCleanupRouting.fromSteps(steps);
                    assertEquals(cleanup, TaskCleanupRouting.fromParameters(cleanup.parameters()));
                    assertFalse(cleanup.parameters().containsKey("robotWarehouseFirstEnabled"));
                    if (robot) {
                        var warehouse = steps.stream().filter(step -> ConfigValidator.ROBOT_WAREHOUSE
                                .equals(step.moduleId())).findFirst().orElseThrow();
                        assertEquals(total, Boolean.parseBoolean(warehouse.parameters()
                                .get(ClassificationRemainderPolicy.TOTAL_OVERFLOW_FOLLOWS)));
                    }
                }
            }
        }
    }

    @Test
    void savedStepsMigrateBothRetiredOrdersWithoutChangingOtherStagesOrStepIds() {
        for (var kind : List.of(AutoClassifyDefinition.Kind.CLASSIFICATION,
                AutoClassifyDefinition.Kind.COMBINED)) {
            for (boolean retired : List.of(false, true)) {
                var definition = definition(kind, true, true);
                var tasks = new ArrayList<>(AutoClassifyTaskFactory.tasks(definition, List.of()));
                var task = classification(tasks, definition);
                var legacy = new ArrayList<>(task.steps());
                var robot = legacy.stream().filter(step -> ConfigValidator.ROBOT_WAREHOUSE
                        .equals(step.moduleId())).findFirst().orElseThrow();
                legacy.remove(robot);
                var parameters = new LinkedHashMap<>(robot.parameters());
                parameters.remove(ClassificationRemainderPolicy.TOTAL_OVERFLOW_FOLLOWS);
                parameters.put("robotWarehouseFirstEnabled", Boolean.toString(retired));
                int total = 0;
                while (!ClassificationRemainderPolicy.OVERFLOW.equals(legacy.get(total)
                        .parameters().get(ClassificationRemainderPolicy.KIND))) total++;
                legacy.add(retired ? total : total + 1,
                        TaskStep.action(robot.id(), robot.moduleId(), parameters));
                tasks.set(tasks.indexOf(task), new TaskDefinition(task.id(), task.name(),
                        task.priority(), task.repeatWhenIdle(), legacy, task.interruptionMode(),
                        task.interruptionTaskRef(), task.defaultInterruptionFlow(),
                        task.requeueAfterInterruption(), task.folderId(), task.cleanupPolicy()));
                var normalized = TaskFlowNormalizer.normalize(config(definition, tasks));
                assertEquals(task.steps(), classification(normalized.tasks(), definition).steps());
                assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
            }
        }
    }

    @Test
    void pickupAndOrdinaryCleanupDoNotAcquireAClassificationHandoff() {
        var pickup = definition(AutoClassifyDefinition.Kind.PICKUP, true, true);
        assertTrue(AutoClassifyTaskFactory.tasks(pickup, List.of()).stream()
                .flatMap(task -> task.steps().stream()).allMatch(step -> !step.parameters()
                        .containsKey(ClassificationRemainderPolicy.TOTAL_OVERFLOW_FOLLOWS)));
        var cleanup = new TaskCleanupRouting(true, null, null, true);
        assertEquals(cleanup, TaskCleanupRouting.fromParameters(cleanup.parameters()));
        assertTrue(cleanup.legacyOverflowEnabled());
    }

    private static List<String> routing(List<TaskStep> steps) {
        return steps.stream().filter(step -> ClassificationRemainderPolicy.MODULE
                        .equals(step.moduleId()) || ConfigValidator.ROBOT_WAREHOUSE
                        .equals(step.moduleId()))
                .map(step -> step.parameters().getOrDefault(
                        ClassificationRemainderPolicy.KIND, step.moduleId())).toList();
    }

    private static TaskDefinition classification(List<TaskDefinition> tasks,
            AutoClassifyDefinition definition) {
        return tasks.stream().filter(task -> task.id().equals(definition.classificationTaskId()))
                .findFirst().orElseThrow();
    }

    private static AppConfig config(AutoClassifyDefinition definition, List<TaskDefinition> tasks) {
        return new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks, List.of(),
                List.of(), List.of(), List.of(), List.of(definition));
    }

    private static AutoClassifyDefinition definition(AutoClassifyDefinition.Kind kind,
            boolean total, boolean robot) {
        var remainder = new ClassificationRemainderPolicy(true, ref("coordinate"), total,
                ref("region"));
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
