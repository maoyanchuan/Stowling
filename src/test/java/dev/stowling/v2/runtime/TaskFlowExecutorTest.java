package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleIds;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskFlowExecutorTest {
    @Test
    void fastStepsAreRecordedEvenWhenBothCompleteDuringOneMonitorTick() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        workspace.setDiagnostics(log);
        AtomicInteger count = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of("COUNT", (context, step) -> action(() ->
                success("counted", count.incrementAndGet()))),
                List.of(actionStep("COUNT"), actionStep("COUNT")));

        assertEquals(TaskModuleState.SUCCEEDED, flow.tick().state());
        assertEquals(2, count.get());
        var events = log.read(0, 100).events();
        assertEquals(2, events.stream().filter(event -> event.kind().equals("STEP")).count());
        assertTrue(events.stream().anyMatch(event -> event.step().equals("1")
                && event.kind().equals("RESULT")));
        assertTrue(events.stream().anyMatch(event -> event.step().equals("2")
                && event.kind().equals("RESULT")));
    }
    @Test
    void repeatStopsWhenConditionTurnsFalse() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setCacheHasMore(true);
        AtomicInteger count = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "TOGGLE_CACHE", (context, step) -> action(() -> {
                    context.workspace().setCacheHasMore(false);
                    return TaskModuleResult.succeeded("toggled");
                }),
                "COUNT", (context, step) -> action(() ->
                        success("counted", count.incrementAndGet())
                )
        ), List.of(TaskStep.repeat(
                UUID.randomUUID(),
                Map.of(TaskModuleIds.CONDITION, "CACHE_HAS_MORE"),
                List.of(
                        actionStep("TOGGLE_CACHE"),
                        actionStep("COUNT")
                )
        )));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(1, count.get());
    }

    @Test
    void managedItemsConditionCanTrackCollectedSubLedger() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordCollected("minecraft:gold_ingot", 1);
        workspace.recordWithdrawn("minecraft:iron_ingot", 1);
        AtomicInteger count = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "CLEAR_COLLECTED", (context, step) -> action(() -> {
                    context.workspace().removeCollected(
                            "minecraft:gold_ingot",
                            1
                    );
                    count.incrementAndGet();
                    return TaskModuleResult.succeeded("cleared");
                })
        ), List.of(TaskStep.repeat(
                UUID.randomUUID(),
                Map.of(
                        TaskModuleIds.CONDITION,
                        "HAS_MANAGED_ITEMS",
                        TaskModuleIds.ITEM_SOURCE,
                        "collected"
                ),
                List.of(actionStep("CLEAR_COLLECTED"))
        )));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(1, count.get());
        assertEquals(
                Map.of("minecraft:iron_ingot", 1),
                workspace.carriedItems()
        );
    }

    @Test
    void repeatAlwaysHonorsMaxIterations() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        AtomicInteger count = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "COUNT", (context, step) -> action(() ->
                        success("counted", count.incrementAndGet())
                )
        ), List.of(TaskStep.repeat(
                UUID.randomUUID(),
                Map.of(
                        TaskModuleIds.CONDITION, "ALWAYS",
                        TaskModuleIds.MAX_ITERATIONS, "3"
                ),
                List.of(actionStep("COUNT"))
        )));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals(3, count.get());
        assertTrue(result.message().contains("最大迭代次数"));
    }

    @Test
    void conditionalSelectsFoodThresholdBranch() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        AtomicInteger count = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "COUNT", (context, step) -> action(() ->
                        success("counted", count.incrementAndGet())
                )
        ), List.of(TaskStep.conditional(
                UUID.randomUUID(),
                Map.of(
                        TaskModuleIds.CONDITION, "FOOD_BELOW_THRESHOLD",
                        TaskModuleIds.FOOD_LEVEL_THRESHOLD, "18"
                ),
                List.of(actionStep("COUNT")),
                List.of()
        )));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(1, count.get());
    }

    @Test
    void actionCreationFailureReportsStepAndPreservesProgress() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "FIRST", (context, step) -> action(
                        () -> TaskModuleResult.succeeded("first")
                ),
                "BROKEN_CREATE", (context, step) -> {
                    throw new IllegalStateException("create boom");
                }
        ), List.of(
                actionStep("FIRST"),
                actionStep("BROKEN_CREATE")
        ));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals(1, flow.completedTopLevelSteps());
        assertEquals(2, flow.totalTopLevelSteps());
        assertTrue(flow.safeToStop());
        assertTrue(result.message().contains("第 2 步（BROKEN_CREATE）"));
        assertTrue(result.message().contains("create boom"));
    }

    @Test
    void nestedActionStartFailureReportsPathAndPreservesProgress() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "FIRST", (context, step) -> action(
                        () -> TaskModuleResult.succeeded("first")
                ),
                "BROKEN_START", (context, step) -> action(() -> {
                    throw new IllegalStateException("start boom");
                })
        ), List.of(
                actionStep("FIRST"),
                TaskStep.repeat(
                        UUID.randomUUID(),
                        Map.of(TaskModuleIds.CONDITION, "ALWAYS"),
                        List.of(actionStep("BROKEN_START"))
                )
        ));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals(1, flow.completedTopLevelSteps());
        assertTrue(result.message().contains("第 2.1 步（BROKEN_START）"));
        assertTrue(result.message().contains("start boom"));
    }

    @Test
    void actionTickFailureReportsStepAndPreservesProgress() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "FIRST", (context, step) -> action(
                        () -> TaskModuleResult.succeeded("first")
                ),
                "WAIT_THEN_THROW", (context, step) -> action(
                        () -> TaskModuleResult.waiting("waiting"),
                        () -> {
                            throw new IllegalStateException("tick boom");
                        }
                )
        ), List.of(
                actionStep("FIRST"),
                actionStep("WAIT_THEN_THROW")
        ));

        TaskModuleResult waiting = flow.tick();
        TaskModuleResult failed = flow.tick();

        assertEquals(TaskModuleState.WAITING, waiting.state());
        assertEquals(TaskModuleState.FAILED, failed.state());
        assertEquals(1, flow.completedTopLevelSteps());
        assertTrue(failed.message().contains("第 2 步（WAIT_THEN_THROW）"));
        assertTrue(failed.message().contains("tick boom"));
    }

    @Test
    void stoppingActionFailureKeepsStepLocation() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "STOP_FAIL", (context, step) -> action(
                        () -> TaskModuleResult.waiting("waiting"),
                        () -> {
                            throw new IllegalStateException("stop boom");
                        }
                )
        ), List.of(actionStep("STOP_FAIL")));

        assertEquals(TaskModuleState.WAITING, flow.tick().state());
        flow.requestStop();
        TaskModuleResult result = flow.tickWhileStopping();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals(0, flow.completedTopLevelSteps());
        assertTrue(result.message().contains("第 1 步（STOP_FAIL）"));
        assertTrue(result.message().contains("stop boom"));
    }

    @Test
    void startExceptionRetainsUnsafeActionUntilCancelled() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        AtomicInteger requestStops = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "UNSAFE_START", (context, step) -> new UnsafeExecutor(
                        true,
                        requestStops,
                        cancels
                )
        ), List.of(actionStep("UNSAFE_START")));

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertFalse(flow.safeToStop());
        flow.requestStop();
        assertEquals(1, requestStops.get());
        assertFalse(flow.safeToStop());
        flow.cancel();
        assertEquals(1, cancels.get());
        assertTrue(flow.safeToStop());
    }

    @Test
    void stoppingTickExceptionRetainsUnsafeActionUntilCancelled() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        AtomicInteger requestStops = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        TaskFlowExecutor flow = flow(workspace, Map.of(
                "UNSAFE_TICK", (context, step) -> new UnsafeExecutor(
                        false,
                        requestStops,
                        cancels
                )
        ), List.of(actionStep("UNSAFE_TICK")));

        assertEquals(TaskModuleState.WAITING, flow.tick().state());
        flow.requestStop();
        TaskModuleResult result = flow.tickWhileStopping();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals(1, requestStops.get());
        assertFalse(flow.safeToStop());
        assertTrue(result.message().contains("第 1 步（UNSAFE_TICK）"));
        flow.cancel();
        assertEquals(1, cancels.get());
        assertTrue(flow.safeToStop());
    }

    @Test
    void createTaskOmitsPriorityForTargetTaskDefault() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        AtomicReference<TaskPriority> observed = new AtomicReference<>();
        TaskFlowExecutor flow = new TaskFlowExecutor(
                List.of(TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of(
                                TaskModuleIds.TASK_REF,
                                "task:" + UUID.randomUUID()
                        )
                )),
                context(workspace, Map.of()),
                new TaskFlowExecutor.Host() {
                    @Override
                    public TaskModuleExecutor createAction(TaskStep step) {
                        throw new AssertionError("no action expected");
                    }

                    @Override
                    public void createTask(
                            String taskRef,
                            TaskPriority priority
                    ) {
                        observed.set(priority);
                    }
                },
                true
        );

        TaskModuleResult result = flow.tick();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertNull(observed.get());
    }

    private static TaskFlowExecutor flow(
            TaskRunWorkspace workspace,
            Map<String, ActionFactory> factories,
            List<TaskStep> steps
    ) {
        return new TaskFlowExecutor(
                steps,
                context(workspace, factories),
                new TaskFlowExecutor.Host() {
                    @Override
                    public TaskModuleExecutor createAction(TaskStep step) {
                        ActionFactory factory = factories.get(step.moduleId());
                        if (factory == null) {
                            throw new IllegalArgumentException(
                                    "unknown test action: " + step.moduleId()
                            );
                        }
                        return factory.create(context(workspace, factories), step);
                    }

                    @Override
                    public void createTask(
                            String taskRef,
                            TaskPriority priority
                    ) {
                    }
                },
                true
        );
    }

    private static TaskExecutionContext context(
            TaskRunWorkspace workspace,
            Map<String, ActionFactory> factories
    ) {
        NavigationPort navigation = new ArrivedNavigation();
        TaskModuleRuntimeRegistry registry =
                TaskModuleRuntimeRegistry.unavailablePorts(navigation);
        return new TaskExecutionContext(
                navigation,
                registry.droppedItems(),
                registry.containerRegions(),
                registry.containerTransfers(),
                registry.containerWithdrawals(),
                registry.containerInspections(),
                registry.categoryMarkerCache(),
                new FoodPort(),
                registry.categoryZones(),
                Map.of(UUID.randomUUID(), new CoordinatePoint(
                        UUID.randomUUID(), "unused", 0, 64, 0
                )),
                Map.<UUID, RegionDefinition>of(),
                Map.of(),
                workspace
        );
    }

    private static TaskModuleExecutor action(ResultSupplier supplier) {
        return action(supplier, supplier);
    }

    private static TaskModuleExecutor action(
            ResultSupplier start,
            ResultSupplier tick
    ) {
        return new TaskModuleExecutor() {
            @Override
            public TaskModuleResult start() {
                return start.get();
            }

            @Override
            public TaskModuleResult tick() {
                return tick.get();
            }

            @Override
            public void cancel() {
            }
        };
    }

    private static TaskModuleResult success(
            String message,
            int ignored
    ) {
        return TaskModuleResult.succeeded(message);
    }

    private static TaskStep actionStep(String moduleId) {
        return TaskStep.action(
                UUID.randomUUID(),
                moduleId,
                Map.of()
        );
    }

    @FunctionalInterface
    private interface ActionFactory {
        TaskModuleExecutor create(TaskExecutionContext context, TaskStep step);
    }

    @FunctionalInterface
    private interface ResultSupplier {
        TaskModuleResult get();
    }

    private static final class UnsafeExecutor implements TaskModuleExecutor {
        private final boolean startThrows;
        private final AtomicInteger requestStops;
        private final AtomicInteger cancels;

        private UnsafeExecutor(
                boolean startThrows,
                AtomicInteger requestStops,
                AtomicInteger cancels
        ) {
            this.startThrows = startThrows;
            this.requestStops = requestStops;
            this.cancels = cancels;
        }

        @Override
        public TaskModuleResult start() {
            if (startThrows) {
                throw new IllegalStateException("start boom");
            }
            return TaskModuleResult.waiting("waiting");
        }

        @Override
        public TaskModuleResult tick() {
            throw new IllegalStateException("tick boom");
        }

        @Override
        public void requestStop() {
            requestStops.incrementAndGet();
        }

        @Override
        public boolean safeToStop() {
            return false;
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
        }
    }

    private static final class FoodPort implements FoodConsumptionPort {
        @Override
        public FoodStatus observe() {
            return new FoodStatus(true, 10, 0);
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            return FoodConsumptionResult.succeeded("eaten");
        }

        @Override
        public FoodConsumptionResult pollEating() {
            return FoodConsumptionResult.succeeded("eaten");
        }

        @Override
        public void cancelEating() {
        }
    }

    private static final class ArrivedNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return StopOutcome.CLEAN;
        }
    }
}
