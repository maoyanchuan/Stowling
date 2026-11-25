package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskCleanupRoutingTest {
    @Test
    void classificationSwitchesIndependentlyControlCleanupDestinations() {
        for (boolean overflow : List.of(false, true)) {
            for (boolean robot : List.of(false, true)) {
                for (boolean mixed : List.of(false, true)) {
                    var definition = definition(overflow, robot, mixed);
                    var task = AutoClassifyTaskFactory.tasks(definition, List.of()).getFirst();
                    var routing = TaskCleanupRouting.fromSteps(task.steps());

                    assertEquals(overflow ? reference(definition.remainderPolicy()
                            .overflowDestinationRef()) : null, routing.overflowDestination());
                    assertEquals(robot ? reference(definition.overflowDestinationRef()) : null,
                            routing.robotWarehouse());
                    assertEquals(robot ? mixed : true, routing.mixedBoxes());
                    assertFalse(routing.legacyOverflowEnabled());
                    assertEquals(overflow || robot, routing.enabled());
                    assertEquals(routing,
                            TaskCleanupRouting.fromParameters(routing.parameters()));
                }
            }
        }
    }

    @Test
    void unclassifiableDestinationIsNotAnInterruptionFallback() {
        var definition = definition(false, false, true);
        var routing = TaskCleanupRouting.fromSteps(
                AutoClassifyTaskFactory.tasks(definition, List.of()).getFirst().steps());

        assertTrue(definition.remainderPolicy().unclassifiableEnabled());
        assertNull(routing.overflowDestination());
        assertNull(routing.robotWarehouse());
        assertFalse(routing.enabled());
    }

    @Test
    void nestedRobotStepsKeepTheirDestinationAndLegacyDefaultBoxMode() {
        String destination = ref("region");
        var step = TaskStep.conditional(UUID.randomUUID(), Map.of(), List.of(), List.of(
                TaskStep.action(UUID.randomUUID(), ConfigValidator.ROBOT_WAREHOUSE,
                        Map.of(ConfigValidator.TARGET_REF, destination))));
        var routing = TaskCleanupRouting.fromSteps(List.of(step));

        assertEquals(reference(destination), routing.robotWarehouse());
        assertTrue(routing.mixedBoxes());
        assertFalse(routing.legacyOverflowEnabled());
    }

    @Test
    void explicitDestinationsPreventFallbackToTheOldSharedOverflow() {
        var legacy = TaskStep.action(UUID.randomUUID(), ConfigValidator.PACK_OVERFLOW, Map.of());
        assertTrue(TaskCleanupRouting.fromSteps(List.of(legacy)).legacyOverflowEnabled());
        var robot = TaskStep.action(UUID.randomUUID(), ConfigValidator.ROBOT_WAREHOUSE,
                Map.of(ConfigValidator.TARGET_REF, ref("region"), "mixedBoxes", "false"));

        var routing = TaskCleanupRouting.fromSteps(List.of(legacy, robot));

        assertFalse(routing.legacyOverflowEnabled());
        assertFalse(routing.mixedBoxes());
        assertTrue(routing.enabled());
    }

    private static AutoClassifyDefinition definition(boolean overflow, boolean robot,
            boolean mixed) {
        var remainder = new ClassificationRemainderPolicy(true, ref("region"),
                overflow, ref("coordinate"));
        return new AutoClassifyDefinition(UUID.randomUUID(), "Classification", false, false,
                true, null, null, ref("region"),
                List.of(new AutoClassifyAreaDefinition(ref("category-zone"))),
                TaskPriority.NORMAL, robot, null, null, ref("region"), null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, false,
                remainder, mixed);
    }

    private static String ref(String type) {
        return type + ":" + UUID.randomUUID();
    }

    private static ResourceReference reference(String value) {
        return ResourceReference.parse(value);
    }
}
