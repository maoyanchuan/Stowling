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
                List.of("id", "name", "x", "y", "z"),
                components(CoordinatePoint.class)
        );
    }

    @Test
    void taskAndStepKeepOnlyTheV2Fields() {
        assertEquals(
                List.of("id", "name", "steps"),
                components(TaskDefinition.class)
        );
        assertEquals(
                List.of("id", "moduleId", "parameters"),
                components(TaskStep.class)
        );
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
        assertEquals(stepId, step.id());
        assertEquals("Travel", task.name());
    }

    private static List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }
}
