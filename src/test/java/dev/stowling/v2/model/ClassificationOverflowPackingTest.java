package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassificationOverflowPackingTest {
    @Test
    void oldConfigurationsDefaultOffAndDisabledDraftsSurviveSerialization() {
        var gson = new Gson();
        assertEquals(ClassificationRemainderPolicy.DEFAULT,
                gson.fromJson("{}", ClassificationRemainderPolicy.class));
        var draft = new ClassificationRemainderPolicy(false, null, false, ref("region"), true);
        assertEquals(draft, gson.fromJson(gson.toJson(draft), ClassificationRemainderPolicy.class));
    }

    @Test
    void onlyEnabledIndependentClassificationTotalOverflowPacks() {
        for (var kind : AutoClassifyDefinition.Kind.values()) {
            for (boolean enabled : List.of(false, true)) {
                for (boolean pack : List.of(false, true)) {
                    var definition = definition(kind, enabled, pack);
                    var tasks = AutoClassifyTaskFactory.tasks(definition, List.of());
                    var routing = AutoClassifyTaskFactory.classificationOverflowSteps(definition);
                    boolean effective = kind == AutoClassifyDefinition.Kind.CLASSIFICATION
                            && enabled && pack;
                    assertEquals(effective, routing.stream().anyMatch(step -> Boolean.parseBoolean(
                            step.parameters().get(ClassificationRemainderPolicy
                                    .OVERFLOW_PACKING_ENABLED))));
                    var cleanup = TaskCleanupRouting.fromSteps(routing);
                    assertEquals(effective, cleanup.overflowPackingEnabled());
                    assertEquals(cleanup, TaskCleanupRouting.fromParameters(cleanup.parameters()));
                    assertFalse(cleanup.legacyOverflowEnabled());
                    var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks,
                            List.of(), List.of(), List.of(), List.of(), List.of(definition));
                    var normalized = TaskFlowNormalizer.normalize(config);
                    assertEquals(normalized, TaskFlowNormalizer.normalize(normalized));
                    assertEquals(pack, normalized.autoClassifyTasks().getFirst()
                            .remainderPolicy().overflowPackingEnabled());
                    if (kind == AutoClassifyDefinition.Kind.PICKUP) {
                        assertTrue(tasks.stream().flatMap(task -> task.steps().stream())
                                .noneMatch(step -> step.parameters().containsKey(
                                        ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED)));
                    }
                }
            }
        }
    }

    @Test
    void packingKeepsWarehouseFirstAndDoesNotChangeOtherRemainderStages() {
        var definition = definition(AutoClassifyDefinition.Kind.CLASSIFICATION, true, true);
        var steps = AutoClassifyTaskFactory.tasks(definition, List.of()).getFirst().steps();
        var routes = steps.stream().filter(step -> ClassificationRemainderPolicy.MODULE
                .equals(step.moduleId()) || ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId()))
                .toList();
        assertEquals(List.of("UNCLASSIFIABLE", ConfigValidator.ROBOT_WAREHOUSE, "OVERFLOW"),
                routes.stream().map(step -> step.parameters().getOrDefault(
                        ClassificationRemainderPolicy.KIND, step.moduleId())).toList());
        assertFalse(routes.get(0).parameters().containsKey(
                ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED));
        assertFalse(routes.get(1).parameters().containsKey(
                ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED));
        var snapshot = TaskCleanupRouting.fromSteps(steps);
        assertTrue(snapshot.overflowPackingEnabled());
        assertFalse(snapshot.mixedBoxes());
        assertFalse(TaskCleanupRouting.fromSteps(List.of(TaskStep.action(UUID.randomUUID(),
                ConfigValidator.PACK_OVERFLOW, Map.of()))).overflowPackingEnabled());
        assertFalse(TaskCleanupRouting.fromSteps(AutoClassifyTaskFactory.tasks(
                definition(AutoClassifyDefinition.Kind.CLASSIFICATION, false, true), List.of())
                .getFirst().steps()).overflowPackingEnabled());
        assertTrue(snapshot.overflowPackingEnabled());
    }

    private static AutoClassifyDefinition definition(AutoClassifyDefinition.Kind kind,
            boolean enabled, boolean pack) {
        var policy = new ClassificationRemainderPolicy(true, ref("coordinate"), enabled,
                ref("region"), pack);
        return new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, true, true,
                ref("coordinate"), 8, ref("region"),
                List.of(new AutoClassifyAreaDefinition(ref("category-zone"))),
                TaskPriority.NORMAL, true, null, null, ref("region"), null, kind,
                null, null, null, false, policy, false);
    }

    private static String ref(String type) {
        return type + ":" + UUID.randomUUID();
    }
}
