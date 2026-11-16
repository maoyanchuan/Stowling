package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                registry.containerCategories(),
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
        return new TaskModuleExecutor() {
            @Override
            public TaskModuleResult start() {
                return supplier.get();
            }

            @Override
            public TaskModuleResult tick() {
                return supplier.get();
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
