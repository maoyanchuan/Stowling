package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V2ModelContractTest {
    @Test
    void coordinateContainsOnlyHiddenIdNameAndXyz() {
        assertEquals(
                List.of("id", "name", "x", "y", "z", "dimension", "radius"),
                components(CoordinatePoint.class)
        );
    }

    @Test
    void taskAndStepKeepOnlyTheV2Fields() {
        assertEquals(
                List.of(
                        "id",
                        "name",
                        "priority",
                        "repeatWhenIdle",
                        "steps",
                        "trigger"
                ),
                components(TaskDefinition.class)
        );
        assertEquals(
                List.of("id", "moduleId", "parameters"),
                components(TaskStep.class)
        );
        assertEquals(
                List.of(
                        "type",
                        "coordinateId",
                        "itemFilter",
                        "intervalTicks",
                        "cooldownTicks"
                ),
                components(TaskTriggerDefinition.class)
        );
    }

    @Test
    void regionContainsOnlyHiddenIdNameAndTwoCorners() {
        RegionDefinition region = new RegionDefinition(
                UUID.randomUUID(),
                " Farm ",
                0,
                64,
                0,
                10,
                70,
                10
        );

        assertEquals(
                List.of(
                        "id",
                        "name",
                        "firstX",
                        "firstY",
                        "firstZ",
                        "secondX",
                        "secondY",
                        "secondZ"
                ),
                components(RegionDefinition.class)
        );
        assertEquals("Farm", region.name());
    }

    @Test
    void idsAreUuidsAndNamesAreTrimmed() {
        UUID coordinateId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        CoordinatePoint point = new CoordinatePoint(
                coordinateId,
                " Home ",
                1,
                64,
                2
        );
        TaskStep step = new TaskStep(stepId, "MOVE_TO_COORDINATE", java.util.Map.of());
        TaskDefinition task = new TaskDefinition(
                UUID.randomUUID(),
                " Travel ",
                List.of(step)
        );

        assertEquals(coordinateId, point.id());
        assertEquals("Home", point.name());
        assertEquals(null, point.dimension());
        assertEquals(null, point.radius());
        assertEquals(stepId, step.id());
        assertEquals("Travel", task.name());
        assertEquals(TaskPriority.NORMAL, task.priority());
        assertEquals(false, task.repeatWhenIdle());
        assertEquals(null, task.trigger());
    }

    private static List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }
}
