package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.VersionedTaskDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskDefinitionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void taskDefinitionUsesOuterSettingsForPickupWorkflow() {
        PickupWorkflow pickup = ConfigTestFixtures.config(List.of())
                .pickupWorkflows()
                .getFirst();
        TaskSettings outerSettings = settings(TaskPriority.HIGH);
        TaskDefinition definition = new TaskDefinition(
                TaskKey.PICKUP,
                pickup.id(),
                pickup.name(),
                outerSettings,
                pickup,
                List.of()
        );

        assertEquals(outerSettings, definition.toPickup().settings());
    }

    @Test
    void updatesOnlyTheTargetTaskMember() {
        Path path = tempDir.resolve("stowling.json");
        ConfigLoader loader = new ConfigLoader();
        StowlingConfig initial = ConfigTestFixtures.config(List.of());
        loader.write(path, initial);
        ConfigFileStore fileStore = new ConfigFileStore(path, loader);
        TaskDefinitionStore tasks = new TaskDefinitionStore(fileStore);
        TaskDefinition pickup = TaskDefinition.from(initial.pickupWorkflows().getFirst());
        TaskDefinition automation = automation("automation-one");
        tasks.create(automation);
        VersionedTaskDefinition pickupBefore = tasks.get(pickup.key());

        TaskDefinition updated = withPickupSettings(
                pickup.toPickup(),
                settings(TaskPriority.HIGH)
        );
        VersionedTaskDefinition result = tasks.update(
                pickup.key(),
                pickupBefore.revision(),
                updated
        );

        assertEquals(TaskPriority.HIGH, result.definition().settings().priority());
        assertEquals(automation, tasks.get(automation.key()).definition());
        assertEquals(initial.storageRoutes(), fileStore.read().config().storageRoutes());
        assertNotEquals(pickupBefore.revision(), result.revision());
    }

    @Test
    void taskRevisionDoesNotIncludeOtherTasks() {
        Path path = tempDir.resolve("task-scoped-revision.json");
        ConfigLoader loader = new ConfigLoader();
        AutomationWorkflow first = automation("first").toAutomation();
        AutomationWorkflow second = automation("second").toAutomation();
        StowlingConfig initial = ConfigTestFixtures.configWithAutomation(
                List.of(first, second)
        );
        loader.write(path, initial);
        TaskDefinitionStore tasks = new TaskDefinitionStore(
                new ConfigFileStore(path, loader)
        );
        VersionedTaskDefinition firstBefore = tasks.get(new TaskKey(TaskKey.AUTOMATION, "first"));
        VersionedTaskDefinition secondBefore = tasks.get(
                new TaskKey(TaskKey.AUTOMATION, "second")
        );

        tasks.update(
                secondBefore.key(),
                secondBefore.revision(),
                withAutomationSettings(second, settings(TaskPriority.HIGH))
        );

        TaskDefinition firstUpdate = withAutomationSettings(
                first,
                settings(TaskPriority.LOW)
        );
        assertDoesNotThrow(() -> tasks.update(
                firstBefore.key(),
                firstBefore.revision(),
                firstUpdate
        ));
    }

    @Test
    void rejectsDuplicateCreateAndMissingTask() {
        Path path = tempDir.resolve("task-errors.json");
        ConfigLoader loader = new ConfigLoader();
        StowlingConfig initial = ConfigTestFixtures.config(List.of());
        loader.write(path, initial);
        TaskDefinitionStore tasks = new TaskDefinitionStore(
                new ConfigFileStore(path, loader)
        );
        TaskDefinition pickup = TaskDefinition.from(initial.pickupWorkflows().getFirst());

        assertThrows(IllegalArgumentException.class, () -> tasks.create(pickup));
        assertThrows(
                TaskDefinitionNotFoundException.class,
                () -> tasks.get(new TaskKey(TaskKey.AUTOMATION, "missing"))
        );
    }

    @Test
    void rejectsStaleTaskRevisionAndDeleteRemovesOnlyTarget() {
        Path path = tempDir.resolve("task-delete.json");
        ConfigLoader loader = new ConfigLoader();
        StowlingConfig initial = ConfigTestFixtures.config(List.of());
        loader.write(path, initial);
        TaskDefinitionStore tasks = new TaskDefinitionStore(
                new ConfigFileStore(path, loader)
        );
        TaskDefinition first = automation("first");
        TaskDefinition second = automation("second");
        VersionedTaskDefinition firstCreated = tasks.create(first);
        VersionedTaskDefinition secondCreated = tasks.create(second);
        VersionedTaskDefinition firstUpdated = tasks.update(
                firstCreated.key(),
                firstCreated.revision(),
                withAutomationSettings(first.toAutomation(), settings(TaskPriority.HIGH))
        );

        assertThrows(
                ConfigRevisionConflictException.class,
                () -> tasks.delete(firstCreated.key(), firstCreated.revision())
        );

        tasks.delete(secondCreated.key(), secondCreated.revision());
        assertEquals(firstUpdated.definition(), tasks.get(first.key()).definition());
        assertThrows(
                TaskDefinitionNotFoundException.class,
                () -> tasks.get(second.key())
        );
    }

    private static TaskDefinition automation(String id) {
        AutomationStep step = new AutomationStep(
                "wait-" + id,
                AutomationStepType.WAIT,
                Map.of("seconds", "1")
        );
        AutomationWorkflow workflow = new AutomationWorkflow(
                id,
                id,
                List.of(step)
        );
        return TaskDefinition.from(workflow);
    }

    private static TaskDefinition withAutomationSettings(
            AutomationWorkflow workflow,
            TaskSettings settings
    ) {
        return TaskDefinition.from(new AutomationWorkflow(
                workflow.id(),
                workflow.name(),
                workflow.steps(),
                settings
        ));
    }

    private static TaskDefinition withPickupSettings(
            PickupWorkflow workflow,
            TaskSettings settings
    ) {
        return TaskDefinition.from(new PickupWorkflow(
                workflow.id(),
                workflow.name(),
                workflow.dimension(),
                workflow.regions(),
                workflow.cacheIds(),
                workflow.cachePolicy(),
                workflow.classificationTiming(),
                workflow.completionMode(),
                workflow.completionIdleDelay(),
                settings
        ));
    }

    private static TaskSettings settings(TaskPriority priority) {
        return new TaskSettings(
                priority,
                new TaskTrigger(),
                false,
                TaskInterruption.none()
        );
    }
}
