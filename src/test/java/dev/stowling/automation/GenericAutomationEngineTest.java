package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GenericAutomationEngineTest {
    @Test
    void registryRejectsDuplicateAndUnknownModules() {
        AutomationModuleRegistry registry = new AutomationModuleRegistry();
        registry.register(new TestModule(
                AutomationStepType.WAIT,
                context -> AutomationModuleResult.succeeded(Map.of(), "waited")
        ));
        TestModule duplicateModule = new TestModule(
                AutomationStepType.WAIT,
                context -> AutomationModuleResult.succeeded(Map.of(), "again")
        );

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> registry.register(duplicateModule)
        );
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> registry.require(AutomationStepType.CRAFT_ITEM)
        );

        assertTrue(duplicate.getMessage().contains("already registered"));
        assertTrue(unknown.getMessage().contains("unknown automation module"));
    }

    @Test
    void executesStepsInOrderAndTracksConfirmedActions() {
        List<AutomationStepType> calls = new ArrayList<>();
        AutomationModuleRegistry registry = new AutomationModuleRegistry();
        registry.register(recordingModule(AutomationStepType.WAIT, calls));
        registry.register(recordingModule(AutomationStepType.NAVIGATE, calls));
        AutomationWorkflow workflow = workflow(
                step("pause", AutomationStepType.WAIT),
                step("travel", AutomationStepType.NAVIGATE)
        );
        GenericAutomationEngine engine = engine(workflow, registry);

        GenericAutomationSnapshot first = engine.tick();
        GenericAutomationSnapshot second = engine.tick();

        assertEquals(List.of(AutomationStepType.WAIT, AutomationStepType.NAVIGATE), calls);
        assertEquals(1, first.stepIndex());
        assertEquals(AutomationExecutionStatus.RUNNING, first.status());
        assertEquals(2, second.stepIndex());
        assertEquals(AutomationExecutionStatus.SUCCEEDED, second.status());
        assertEquals(
                Optional.of("confirmed-NAVIGATE"),
                second.lastConfirmedAction()
        );
    }

    @Test
    void propagatesPausedAndFailedResults() {
        AutomationModuleRegistry pausingRegistry = new AutomationModuleRegistry();
        pausingRegistry.register(new TestModule(
                AutomationStepType.WAIT,
                context -> AutomationModuleResult.paused("screen is not ready")
        ));
        GenericAutomationEngine pausing = engine(
                workflow(step("wait", AutomationStepType.WAIT)),
                pausingRegistry
        );

        GenericAutomationSnapshot paused = pausing.tick();

        assertEquals(AutomationExecutionStatus.PAUSED, paused.status());
        assertTrue(paused.message().orElseThrow().contains("not ready"));

        AutomationModuleRegistry failingRegistry = new AutomationModuleRegistry();
        failingRegistry.register(new TestModule(
                AutomationStepType.NAVIGATE,
                context -> AutomationModuleResult.failed("path unavailable")
        ));
        GenericAutomationEngine failing = engine(
                workflow(step("travel", AutomationStepType.NAVIGATE)),
                failingRegistry
        );

        GenericAutomationSnapshot failed = failing.tick();

        assertEquals(AutomationExecutionStatus.FAILED, failed.status());
        assertTrue(failed.message().orElseThrow().contains("path unavailable"));
    }

    @Test
    void restoresModuleStateWithoutPersistingUnconfirmedAction() {
        UUID taskId = UUID.randomUUID();
        AutomationWorkflow workflow = workflow(
                step("deposit", AutomationStepType.TAKE_ITEMS)
        );
        InMemoryGenericAutomationCheckpointStore checkpoints =
                new InMemoryGenericAutomationCheckpointStore();
        AutomationModuleRegistry firstRegistry = new AutomationModuleRegistry();
        firstRegistry.register(new TestModule(
                AutomationStepType.TAKE_ITEMS,
                context -> AutomationModuleResult.running(Map.of(
                "screen", "opened",
                "candidateSlot", "12"
        ))
        ));
        GenericAutomationEngine firstRun = new GenericAutomationEngine(
                taskId,
                workflow,
                firstRegistry,
                checkpoints
        );

        firstRun.tick();
        GenericAutomationCheckpoint checkpoint = checkpoints.load(taskId).orElseThrow();

        assertEquals(0, checkpoint.stepIndex());
        assertEquals("opened", checkpoint.moduleState().get("screen"));
        assertTrue(checkpoint.lastConfirmedAction().isEmpty());

        List<Map<String, String>> restoredStates = new ArrayList<>();
        List<Boolean> recoveredFlags = new ArrayList<>();
        AutomationModuleRegistry secondRegistry = new AutomationModuleRegistry();
        secondRegistry.register(new TestModule(
                AutomationStepType.TAKE_ITEMS,
                context -> {
                    restoredStates.add(context.moduleState());
                    recoveredFlags.add(context.recovered());
                    return AutomationModuleResult.succeeded(
                            Map.of(),
                            "slot-confirmed"
                    );
                }
        ));
        GenericAutomationEngine secondRun = new GenericAutomationEngine(
                taskId,
                workflow,
                secondRegistry,
                checkpoints
        );

        GenericAutomationSnapshot recovered = secondRun.tick();

        assertEquals("opened", restoredStates.getFirst().get("screen"));
        assertEquals(List.of(true), recoveredFlags);
        assertEquals(1, recovered.stepIndex());
        assertEquals(AutomationExecutionStatus.SUCCEEDED, recovered.status());
        assertEquals(Optional.of("slot-confirmed"), recovered.lastConfirmedAction());
    }

    @Test
    void rejectsExecutionWhenAWorkflowUsesAnUnknownModule() {
        GenericAutomationEngine engine = engine(
                workflow(step("craft", AutomationStepType.CRAFT_ITEM)),
                new AutomationModuleRegistry()
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                engine::tick
        );

        assertTrue(exception.getMessage().contains("unknown automation module"));
    }

    private static GenericAutomationEngine engine(
            AutomationWorkflow workflow,
            AutomationModuleRegistry registry
    ) {
        return new GenericAutomationEngine(
                UUID.randomUUID(),
                workflow,
                registry,
                new InMemoryGenericAutomationCheckpointStore()
        );
    }

    private static AutomationModule recordingModule(
            AutomationStepType type,
            List<AutomationStepType> calls
    ) {
        return new TestModule(type, context -> {
            calls.add(type);
            return AutomationModuleResult.succeeded(
                    Map.of(),
                    "confirmed-" + type
            );
        });
    }

    private static AutomationWorkflow workflow(AutomationStep... steps) {
        return new AutomationWorkflow("test-workflow", "Test workflow", List.of(steps));
    }

    private static AutomationStep step(String id, AutomationStepType type) {
        return new AutomationStep(id, type, Map.of());
    }

    private record TestModule(
            AutomationStepType type,
            Function<AutomationModuleContext, AutomationModuleResult> function
    ) implements AutomationModule {
        @Override
        public AutomationModuleDescriptor descriptor() {
            return new AutomationModuleDescriptor(type, type.name());
        }

        @Override
        public AutomationModuleResult execute(AutomationModuleContext context) {
            return function.apply(context);
        }
    }
}
