package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {
    @Test
    void trimsCoordinateNamesAndRejectsInvalidLengths() {
        CoordinatePoint point = new CoordinatePoint(
                UUID.randomUUID(),
                " Home ",
                0,
                64,
                0
        );

        assertEquals("Home", point.name());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(UUID.randomUUID(), "  ", 0, 64, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(
                        UUID.randomUUID(),
                        "x".repeat(81),
                        0,
                        64,
                        0
                )
        );
    }

    @Test
    void validatesOptionalDimensionAndRadius() {
        CoordinatePoint point = new CoordinatePoint(
                UUID.randomUUID(),
                "Home",
                0,
                64,
                0,
                " minecraft:overworld ",
                5
        );

        assertEquals("minecraft:overworld", point.dimension());
        assertEquals(5, point.radius());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(
                        UUID.randomUUID(),
                        "Home",
                        0,
                        64,
                        0,
                        "overworld",
                        5
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinatePoint(
                        UUID.randomUUID(),
                        "Home",
                        0,
                        64,
                        0,
                        "minecraft:overworld",
                        129
                )
        );
    }

    @Test
    void rejectsCoordinateNamesThatDifferOnlyByCaseAndWhitespace() {
        AppConfig config = config(
                List.of(
                        new CoordinatePoint(UUID.randomUUID(), "Home", 0, 64, 0),
                        new CoordinatePoint(UUID.randomUUID(), " home ", 1, 64, 1)
                ),
                List.of()
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );
    }

    @Test
    void rejectsTaskNamesThatDifferOnlyByCaseAndWhitespace() {
        CoordinatePoint point = point("Home");
        TaskDefinition first = task("Travel", step(point.id()));
        TaskDefinition second = task(" travel ", step(point.id()));

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(first, second)
                ))
        );
    }

    @Test
    void requiresAtLeastOneTaskStep() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskDefinition(UUID.randomUUID(), "Travel", List.of())
        );
    }

    @Test
    void rejectsUnknownTaskModule() {
        CoordinatePoint point = point("Home");
        TaskStep step = new TaskStep(
                UUID.randomUUID(),
                "UNKNOWN_MODULE",
                Map.of(ConfigValidator.COORDINATE_ID, point.id().toString())
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(task("Travel", step))
                ))
        );
    }

    @Test
    void rejectsUnexpectedModuleParameter() {
        CoordinatePoint point = point("Home");
        TaskStep step = new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString(),
                        "radius",
                        "3"
                )
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(task("Travel", step))
                ))
        );
    }

    @Test
    void rejectsMissingCoordinateReference() {
        TaskStep step = step(UUID.randomUUID());

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(),
                        List.of(task("Travel", step))
                ))
        );
    }

    @Test
    void acceptsAValidMoveToCoordinateTask() {
        CoordinatePoint point = point("Home");

        ConfigValidator.requireValid(config(
                List.of(point),
                List.of(task("Travel", step(point.id())))
        ));
    }

    private static AppConfig config(
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks
    ) {
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                tasks
        );
    }

    private static CoordinatePoint point(String name) {
        return new CoordinatePoint(UUID.randomUUID(), name, 0, 64, 0);
    }

    private static TaskDefinition task(String name, TaskStep step) {
        return new TaskDefinition(UUID.randomUUID(), name, List.of(step));
    }

    private static TaskStep step(UUID coordinateId) {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.COORDINATE_ID, coordinateId.toString())
        );
    }
}
