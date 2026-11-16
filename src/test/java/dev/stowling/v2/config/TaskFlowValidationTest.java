package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.TaskCondition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskFlowValidationTest {
    @Test
    void allowsOutputTargetsForMoveAndRejectsFakeItemSources() {
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep withdraw = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.WITHDRAW_ITEMS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode(),
                        ConfigValidator.ITEM_SOURCE,
                        "carried"
                )
        );
        TaskStep deposit = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("storage").encode(),
                        ConfigValidator.ITEM_SOURCE,
                        "classified"
                )
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValidStep(
                move,
                Map.of(),
                Map.of()
        ));
        assertThrows(V2ValidationException.class, () ->
                ConfigValidator.requireValidStep(withdraw, Map.of(), Map.of())
        );
        assertThrows(V2ValidationException.class, () ->
                ConfigValidator.requireValidStep(deposit, Map.of(), Map.of())
        );
    }

    @Test
    void assertItemSourceAcceptsOnlyExplicitSupportedLedgers() {
        TaskStep defaultAssertion = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of()
        );
        assertDoesNotThrow(() -> ConfigValidator.requireValidStep(
                defaultAssertion,
                Map.of(),
                Map.of()
        ));
        for (String source : List.of("collected", "carried", "managed")) {
            TaskStep assertion = TaskStep.action(
                    UUID.randomUUID(),
                    ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                    Map.of(ConfigValidator.ITEM_SOURCE, source)
            );
            assertDoesNotThrow(() -> ConfigValidator.requireValidStep(
                    assertion,
                    Map.of(),
                    Map.of()
            ));
        }

        TaskStep unknown = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of(ConfigValidator.ITEM_SOURCE, "inventory")
        );
        TaskStep blank = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of(ConfigValidator.ITEM_SOURCE, "")
        );
        TaskStep storageSelection = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_STORAGE_TARGET,
                Map.of(ConfigValidator.ITEM_SOURCE, "managed")
        );

        assertThrows(V2ValidationException.class, () ->
                ConfigValidator.requireValidStep(unknown, Map.of(), Map.of())
        );
        assertThrows(V2ValidationException.class, () ->
                ConfigValidator.requireValidStep(blank, Map.of(), Map.of())
        );
        assertThrows(V2ValidationException.class, () ->
                ConfigValidator.requireValidStep(
                        storageSelection,
                        Map.of(),
                        Map.of()
                )
        );
    }

    @Test
    void acceptsFoodThresholdZeroInsideNestedCondition() {
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep conditional = TaskStep.conditional(
                UUID.randomUUID(),
                Map.of(
                        "condition",
                        TaskCondition.FOOD_BELOW_THRESHOLD.name(),
                        ConfigValidator.FOOD_LEVEL_THRESHOLD,
                        "0"
                ),
                List.of(move),
                List.of()
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValidStep(
                conditional,
                Map.of(),
                Map.of()
        ));
    }

    @Test
    void rejectsCustomInterruptionTaskThatCanCreateTasks() {
        UUID childId = UUID.randomUUID();
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.TARGET_REF, "output:container")
        );
        TaskDefinition child = new TaskDefinition(
                childId,
                "Child",
                TaskPriority.NORMAL,
                false,
                List.of(move)
        );
        UUID cleanupId = UUID.randomUUID();
        TaskDefinition cleanup = new TaskDefinition(
                cleanupId,
                "Cleanup",
                TaskPriority.NORMAL,
                false,
                List.of(TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of(ConfigValidator.TASK_REF, "task:" + childId)
                )),
                TaskInterruptionMode.NONE,
                null,
                false,
                false
        );
        TaskDefinition owner = new TaskDefinition(
                UUID.randomUUID(),
                "Owner",
                TaskPriority.NORMAL,
                false,
                List.of(move),
                TaskInterruptionMode.CUSTOM,
                "task:" + cleanupId,
                false,
                false
        );
        AppConfig config = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(child, cleanup, owner)
        );

        assertThrows(
                V2ValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );
    }

    @Test
    void rejectsExcessivelyDeepNestedSteps() {
        TaskStep step = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.TARGET_REF, "output:container")
        );
        for (int index = 0; index <= TaskDefinition.MAX_STEP_DEPTH; index++) {
            step = TaskStep.repeat(
                    UUID.randomUUID(),
                    Map.of("condition", TaskCondition.ALWAYS.name()),
                    List.of(step)
            );
        }
        TaskStep deep = step;

        assertThrows(IllegalArgumentException.class, () ->
                new TaskDefinition(
                        UUID.randomUUID(),
                        "Too deep",
                        TaskPriority.NORMAL,
                        false,
                        List.of(deep)
                )
        );
    }

    @Test
    void rejectsDuplicateNestedStepIds() {
        UUID shared = UUID.randomUUID();
        TaskStep nested = TaskStep.action(
                shared,
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.TARGET_REF, "output:container")
        );
        TaskStep parent = TaskStep.repeat(
                shared,
                Map.of("condition", TaskCondition.ALWAYS.name()),
                List.of(nested)
        );

        assertThrows(IllegalArgumentException.class, () ->
                new TaskDefinition(
                        UUID.randomUUID(),
                        "Duplicate",
                        TaskPriority.NORMAL,
                        false,
                        List.of(parent)
                )
        );
    }
}
