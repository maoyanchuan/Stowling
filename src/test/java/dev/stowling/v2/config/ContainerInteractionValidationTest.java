package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerInteractionValidationTest {
    @Test
    void savesCollectDetectorPlaceholderBeforeTriggerExists() {
        TaskDefinition task = task(interaction("COLLECT", Map.of()));

        assertDoesNotThrow(() -> ConfigValidator.requireValid(config(
                List.of(),
                List.of(task),
                List.of()
        )));
    }

    @Test
    void rejectsMultipleDetectorsWhenCollectUsesDetector() {
        UUID coordinateId = UUID.randomUUID();
        CoordinatePoint point = new CoordinatePoint(
                coordinateId,
                "Drops",
                0,
                64,
                0
        );
        TaskDefinition task = task(interaction("COLLECT", Map.of()));
        List<TaskTriggerDefinition> triggers = List.of(
                detector(task.id(), coordinateId),
                detector(task.id(), coordinateId)
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(point),
                        List.of(task),
                        triggers
                ))
        );
    }

    @Test
    void explicitCollectAreaAllowsMultipleDetectors() {
        UUID coordinateId = UUID.randomUUID();
        CoordinatePoint point = new CoordinatePoint(
                coordinateId,
                "Drops",
                0,
                64,
                0
        );
        TaskDefinition task = task(interaction("COLLECT", Map.of(
                ConfigValidator.AREA_REF,
                "coordinate:" + coordinateId
        )));

        assertDoesNotThrow(() -> ConfigValidator.requireValid(config(
                List.of(point),
                List.of(task),
                List.of(
                        detector(task.id(), coordinateId),
                        detector(task.id(), coordinateId)
                )
        )));
    }

    @Test
    void rejectsUnrelatedAndUnknownInteractionParameters() {
        TaskDefinition extra = task(interaction("WITHDRAW", Map.of(
                ConfigValidator.TARGET_REF,
                "output:container",
                ConfigValidator.ITEM_FILTER,
                "minecraft:iron_ingot"
        )));
        TaskDefinition unknown = task(interaction("UNKNOWN", Map.of()));

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(),
                        List.of(extra),
                        List.of()
                ))
        );
        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(),
                        List.of(unknown),
                        List.of()
                ))
        );
    }

    @Test
    void onlyManagedItemsConditionAcceptsASubLedgerSource() {
        TaskStep select = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_CONTAINER,
                Map.of()
        );
        TaskStep managed = TaskStep.repeat(
                UUID.randomUUID(),
                Map.of(
                        "condition",
                        "HAS_MANAGED_ITEMS",
                        ConfigValidator.ITEM_SOURCE,
                        "collected"
                ),
                List.of(select)
        );
        TaskStep carried = TaskStep.repeat(
                UUID.randomUUID(),
                Map.of(
                        "condition",
                        "HAS_CARRIED_ITEMS",
                        ConfigValidator.ITEM_SOURCE,
                        "collected"
                ),
                List.of(select)
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValidStep(
                managed,
                Map.of()
        ));
        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValidStep(carried, Map.of())
        );
    }

    @Test
    void rejectsMissingCoordinateResource() {
        TaskDefinition task = task(interaction("READ", Map.of(
                ConfigValidator.TARGET_REF,
                "coordinate:" + UUID.randomUUID()
        )));

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config(
                        List.of(),
                        List.of(task),
                        List.of()
                ))
        );
    }

    private static AppConfig config(
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks,
            List<TaskTriggerDefinition> triggers
    ) {
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                tasks,
                List.of(),
                List.of(),
                triggers
        );
    }

    private static TaskDefinition task(TaskStep... steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Interaction",
                TaskPriority.NORMAL,
                false,
                List.of(steps)
        );
    }

    private static TaskStep interaction(
            String operation,
            Map<String, String> parameters
    ) {
        Map<String, String> values = new LinkedHashMap<>(parameters);
        values.put("operation", operation);
        return TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.CONTAINER_INTERACTION,
                values
        );
    }

    private static TaskTriggerDefinition detector(
            UUID taskId,
            UUID coordinateId
    ) {
        return TaskTriggerDefinition.droppedItems(
                UUID.randomUUID(),
                "Detector",
                taskId,
                TaskPriority.NORMAL,
                20,
                100,
                coordinateId,
                null
        );
    }
}
