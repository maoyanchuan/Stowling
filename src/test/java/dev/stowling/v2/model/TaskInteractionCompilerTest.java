package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskInteractionCompilerTest {
    @Test
    void collectInheritsDetectorRangeAndUsesExplicitFilter() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = task(interaction("COLLECT", Map.of(
                ConfigValidator.ITEM_FILTER,
                "minecraft:iron_ingot",
                ConfigValidator.TIMEOUT_SECONDS,
                "30"
        )));
        TaskTriggerDefinition detector = detector(
                task.id(),
                coordinateId,
                "minecraft:diamond"
        );

        TaskDefinition compiled = TaskInteractionCompiler.compile(
                task,
                List.of(detector)
        );
        TaskStep collect = compiled.steps().getFirst();

        assertEquals(
                ConfigValidator.COLLECT_DROPPED_ITEMS,
                collect.moduleId()
        );
        assertEquals(
                "coordinate:" + coordinateId,
                collect.parameters().get(ConfigValidator.AREA_REF)
        );
        assertEquals(
                "minecraft:iron_ingot",
                collect.parameters().get(ConfigValidator.ITEM_FILTER)
        );
        assertEquals(
                "30",
                collect.parameters().get(ConfigValidator.TIMEOUT_SECONDS)
        );
    }

    @Test
    void collectUsesDetectorDefaultFilterWhenNoFilterIsConfigured() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition task = task(interaction("COLLECT", Map.of()));
        TaskStep collect = TaskInteractionCompiler.compile(
                task,
                List.of(detector(
                        task.id(),
                        coordinateId,
                        "minecraft:diamond"
                ))
        ).steps().getFirst();

        assertEquals(
                "minecraft:diamond",
                collect.parameters().get(ConfigValidator.ITEM_FILTER)
        );
    }

    @Test
    void detectorRequiresExactlyOneMatchingTriggerAtRuntime() {
        TaskDefinition task = task(interaction("COLLECT", Map.of()));

        assertThrows(
                TaskInteractionCompiler.DetectorNotBoundException.class,
                () -> TaskInteractionCompiler.compile(task, List.of())
        );

        TaskTriggerDefinition first = detector(
                task.id(),
                UUID.randomUUID(),
                null
        );
        TaskTriggerDefinition second = detector(
                task.id(),
                UUID.randomUUID(),
                null
        );
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaskInteractionCompiler.compile(
                        task,
                        List.of(first, second)
                )
        );
        assertTrue(failure.getMessage().contains("多个掉落物检测器"));
    }

    @Test
    void explicitCollectAreaDoesNotDependOnDetectorCardinality() {
        UUID areaId = UUID.randomUUID();
        TaskDefinition task = task(interaction("COLLECT", Map.of(
                ConfigValidator.AREA_REF,
                "region:" + areaId
        )));

        TaskDefinition compiled = TaskInteractionCompiler.compile(
                task,
                List.of(
                        detector(task.id(), UUID.randomUUID(), null),
                        detector(task.id(), UUID.randomUUID(), null)
                )
        );

        assertEquals(
                "region:" + areaId,
                compiled.steps().getFirst()
                        .parameters()
                        .get(ConfigValidator.AREA_REF)
        );
    }

    @Test
    void readAndWithdrawCompileCoordinateIntoMoveThenExistingAction() {
        UUID readId = UUID.randomUUID();
        UUID withdrawId = UUID.randomUUID();
        TaskDefinition task = task(
                interaction("READ", Map.of(
                        ConfigValidator.TARGET_REF,
                        "coordinate:" + readId
                )),
                interaction("WITHDRAW", Map.of(
                        ConfigValidator.TARGET_REF,
                        "coordinate:" + withdrawId
                ))
        );

        TaskDefinition compiled = TaskInteractionCompiler.compile(task, List.of());

        assertEquals(4, compiled.steps().size());
        assertEquals(
                ConfigValidator.MOVE_TO_COORDINATE,
                compiled.steps().get(0).moduleId()
        );
        assertEquals(
                ConfigValidator.READ_CONTAINER,
                compiled.steps().get(1).moduleId()
        );
        assertEquals(
                ConfigValidator.MOVE_TO_COORDINATE,
                compiled.steps().get(2).moduleId()
        );
        assertEquals(
                "classified",
                compiled.steps().get(3)
                        .parameters()
                        .get(ConfigValidator.ITEM_SOURCE)
        );
    }

    @Test
    void singleAndRegionDepositRetainTheSourceAssertion() {
        UUID coordinateId = UUID.randomUUID();
        UUID regionId = UUID.randomUUID();
        TaskDefinition single = task(interaction("DEPOSIT", Map.of(
                ConfigValidator.TARGET_REF,
                "coordinate:" + coordinateId
        )));
        TaskDefinition region = task(interaction("DEPOSIT", Map.of(
                ConfigValidator.TARGET_REF,
                "region:" + regionId,
                ConfigValidator.ITEM_SOURCE,
                "carried"
        )));

        List<TaskStep> singleSteps = TaskInteractionCompiler.compile(
                single,
                List.of()
        ).steps();
        List<TaskStep> regionSteps = TaskInteractionCompiler.compile(
                region,
                List.of()
        ).steps();

        assertEquals(
                List.of(
                        ConfigValidator.MOVE_TO_COORDINATE,
                        ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                        ConfigValidator.ASSERT_NO_MANAGED_ITEMS
                ),
                moduleIds(singleSteps)
        );
        assertEquals(
                "collected",
                singleSteps.get(1).parameters().get(ConfigValidator.ITEM_SOURCE)
        );
        assertEquals(
                List.of(
                        ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                        "",
                        ConfigValidator.ASSERT_NO_MANAGED_ITEMS
                ),
                moduleIds(regionSteps)
        );
        TaskStep loop = regionSteps.get(1);
        assertEquals(TaskStepKind.REPEAT, loop.kind());
        assertEquals(TaskCondition.HAS_CONTAINERS.name(), loop.parameters()
                .get(TaskFlowTemplates.CONDITION));
        assertEquals(
                ConfigValidator.SELECT_CONTAINER,
                loop.steps().getFirst().moduleId()
        );
        TaskStep guardedDeposit = loop.steps().get(1);
        assertEquals(TaskStepKind.IF, guardedDeposit.kind());
        assertEquals(
                TaskCondition.HAS_CARRIED_ITEMS.name(),
                guardedDeposit.parameters().get(
                        TaskFlowTemplates.CONDITION
                )
        );
        assertEquals(
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                guardedDeposit.steps().get(1).moduleId()
        );
        assertEquals(
                "carried",
                regionSteps.get(2)
                        .parameters()
                        .get(ConfigValidator.ITEM_SOURCE)
        );
    }

    @Test
    void categoryDepositResolvesTargetsBeforeSourceAwareSelection() {
        UUID zoneId = UUID.randomUUID();
        TaskDefinition task = task(interaction("DEPOSIT", Map.of(
                ConfigValidator.TARGET_REF,
                "category-zone:" + zoneId,
                ConfigValidator.ITEM_SOURCE,
                "managed"
        )));

        List<TaskStep> steps = TaskInteractionCompiler.compile(
                task,
                List.of()
        ).steps();

        assertEquals(ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                steps.getFirst().moduleId());
        assertEquals(
                "true",
                steps.getFirst().parameters().get(
                        ConfigValidator.ALLOW_EMPTY_CONTAINER_SCAN
                )
        );
        TaskStep loop = steps.get(1);
        assertEquals(TaskCondition.HAS_MANAGED_ITEMS.name(), loop.parameters()
                .get(TaskFlowTemplates.CONDITION));
        assertEquals(
                ConfigValidator.SELECT_STORAGE_TARGET,
                loop.steps().getFirst().moduleId()
        );
        assertEquals(
                "managed",
                loop.steps().getFirst()
                        .parameters()
                        .get(ConfigValidator.ITEM_SOURCE)
        );
        assertEquals(
                "output:storage",
                loop.steps().get(2)
                        .parameters()
                        .get(ConfigValidator.TARGET_REF)
        );
    }

    @Test
    void categoryDepositConditionTracksOnlyTheSelectedSource() {
        TaskDefinition task = task(interaction("DEPOSIT", Map.of(
                ConfigValidator.TARGET_REF,
                "category-zone:" + UUID.randomUUID(),
                ConfigValidator.ITEM_SOURCE,
                "collected"
        )));

        TaskStep loop = TaskInteractionCompiler.compile(
                task,
                List.of()
        ).steps().get(1);

        assertEquals(
                TaskCondition.HAS_MANAGED_ITEMS.name(),
                loop.parameters().get(TaskFlowTemplates.CONDITION)
        );
        assertEquals(
                "collected",
                loop.parameters().get(ConfigValidator.ITEM_SOURCE)
        );
    }

    @Test
    void findCategoryZoneRejectsContainerTypesForMarkerScan() {
        TaskDefinition task = task(interaction("FIND", Map.of(
                ConfigValidator.TARGET_REF,
                "category-zone:" + UUID.randomUUID(),
                ConfigValidator.CONTAINER_TYPES,
                "minecraft:barrel"
        )));

        assertThrows(
                IllegalArgumentException.class,
                () -> TaskInteractionCompiler.compile(task, List.of())
        );
    }

    @Test
    void rejectsInvalidOperationsReferencesAndUnrelatedParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(interaction("UNKNOWN", Map.of()))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(interaction("FIND", Map.of(
                        ConfigValidator.TARGET_REF,
                        "coordinate:" + UUID.randomUUID()
                )))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(interaction("WITHDRAW", Map.of(
                        ConfigValidator.TARGET_REF,
                        "output:container",
                        ConfigValidator.ITEM_FILTER,
                        "minecraft:iron_ingot"
                )))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(interaction("DEPOSIT", Map.of(
                        ConfigValidator.TARGET_REF,
                        "output:container",
                        ConfigValidator.MAX_VOLUME,
                        "10"
                )))
        );
    }

    private static void validate(TaskStep step) {
        TaskInteractionCompiler.validateCompactStep(step);
    }

    private static List<String> moduleIds(List<TaskStep> steps) {
        return steps.stream().map(TaskStep::moduleId).toList();
    }

    private static TaskDefinition task(TaskStep... steps) {
        return new TaskDefinition(
                UUID.randomUUID(),
                "Interaction",
                List.of(steps)
        );
    }

    private static TaskStep interaction(
            String operation,
            Map<String, String> parameters
    ) {
        java.util.LinkedHashMap<String, String> values =
                new java.util.LinkedHashMap<>(parameters);
        values.put("operation", operation);
        return TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.CONTAINER_INTERACTION,
                values
        );
    }

    private static TaskTriggerDefinition detector(
            UUID taskId,
            UUID coordinateId,
            String itemFilter
    ) {
        return TaskTriggerDefinition.droppedItems(
                UUID.randomUUID(),
                "Detector",
                taskId,
                TaskPriority.NORMAL,
                20,
                100,
                coordinateId,
                itemFilter
        );
    }
}
