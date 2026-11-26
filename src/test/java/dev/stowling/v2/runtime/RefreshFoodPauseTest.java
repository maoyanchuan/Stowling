package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AutoEatOptions;
import dev.stowling.v2.model.ContainerCacheRefresh;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeFactory;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefreshFoodPauseTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "cancel"})
    void mealFinishesItsCleanupThenResumesTheSameRefreshExecutorAndRun(String outcome) {
        var fixture = new Fixture(true);
        var refresh = fixture.begin();
        var meal = fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.manager.tick();
        assertEquals(0, fixture.mealStarts);
        fixture.refresh.confirmed = true;
        fixture.navigation.outcome = StopOutcome.PENDING;
        fixture.advance();
        assertEquals(0, fixture.mealStarts);
        fixture.navigation.outcome = StopOutcome.CLEAN;
        fixture.advance();
        assertEquals(1, fixture.mealStarts);
        assertEquals(refresh.runId(), fixture.manager.pendingTask(fixture.task.id())
                .orElseThrow().runId());
        assertEquals(1, fixture.refresh.starts);
        if (outcome.equals("cancel")) fixture.manager.cancel(meal.runId());
        fixture.mealFailed = outcome.equals("failure");
        fixture.mealDone = true;
        fixture.advance();
        assertEquals(refresh.runId(), fixture.manager.status().orElseThrow().runId());
        assertEquals(1, fixture.refresh.resumes);
        assertEquals(1, fixture.refresh.starts);
        assertFalse(fixture.refresh.stopped);
        fixture.refresh.complete = true;
        fixture.advance();
        assertEquals(TaskRunState.SUCCEEDED, fixture.state(refresh.runId()));
    }

    @Test
    void disabledExperimentKeepsOrdinaryPreemption() {
        var fixture = new Fixture(false);
        var run = fixture.begin();
        fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.advance();
        assertEquals(TaskRunState.PREEMPTED, fixture.state(run.runId()));
        assertEquals(0, fixture.refresh.resumes);
    }

    @Test
    void explicitCancellationWhileEatingEndsRefreshWithoutResumingItsNormalFlow() {
        var fixture = new Fixture(true);
        var run = fixture.begin();
        fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.refresh.confirmed = true;
        fixture.advance();
        fixture.manager.cancel(run.runId());
        fixture.mealDone = true;
        fixture.advance();
        assertEquals(TaskRunState.CANCELLED, fixture.state(run.runId()));
        assertTrue(fixture.refresh.stopped);
        assertFalse(fixture.refresh.complete);
    }

    @Test
    void unknownNavigationStopNeverHandsTheSlotToEating() {
        var fixture = new Fixture(true);
        fixture.begin();
        fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.refresh.confirmed = true;
        fixture.navigation.outcome = StopOutcome.UNSAFE;
        fixture.advance();
        assertEquals(0, fixture.mealStarts);
    }

    @Test
    void moduleFailureWhileWaitingForNavigationStopCannotLeaveAStalePausedRun() {
        var fixture = new Fixture(true);
        var run = fixture.begin();
        fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.refresh.confirmed = true;
        fixture.navigation.outcome = StopOutcome.PENDING;
        fixture.advance();
        fixture.refresh.failed = true;
        fixture.navigation.outcome = StopOutcome.CLEAN;
        fixture.advance();
        assertEquals(TaskRunState.FAILED, fixture.state(run.runId()));
        assertEquals(0, fixture.refresh.resumes);
    }

    private static final class Fixture {
        private final TestNavigation navigation = new TestNavigation();
        private final PausableRefresh refresh = new PausableRefresh();
        private final TaskDefinition task;
        private final TaskDefinition meal;
        private final AppConfig config;
        private final TaskRunManager manager;
        private boolean mealDone;
        private boolean mealFailed;
        private int mealStarts;

        private Fixture(boolean enabled) {
            var region = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 8, 64, 0);
            task = new TaskDefinition(UUID.randomUUID(), "Refresh", TaskPriority.NORMAL, false,
                    List.of(TaskStep.action(UUID.randomUUID(), ContainerCacheRefresh.MODULE,
                            Map.of("targetRef", "region:" + region.id(), "foodPauseResumeEnabled",
                                    Boolean.toString(enabled)))),
                    TaskInterruptionMode.NONE, null, false, false, null);
            meal = new TaskDefinition(UUID.randomUUID(), "Eat", TaskPriority.URGENT, false,
                    List.of(TaskStep.action(UUID.randomUUID(), "EAT_FOOD", Map.of())),
                    TaskInterruptionMode.NONE, null, false, false, null, null,
                    new AutoEatOptions(false, null, 64, true));
            config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                    List.of(task, meal), List.of(region), List.of());
            var unavailable = TaskModuleRuntimeRegistry.unavailablePorts(navigation);
            var observer = new DroppedItemObserverPort() {
                @Override
                public List<DroppedItem> observe(BlockPosition center, int radius) {
                    return List.of();
                }

                @Override
                public List<DroppedItem> observe(RegionDefinition area) {
                    return List.of();
                }

                @Override
                public Optional<Map<String, Integer>> playerItemCounts() {
                    return Optional.of(Map.of());
                }
            };
            var registry = new TaskModuleRuntimeRegistry(navigation, observer,
                    unavailable.containerRegions(), unavailable.containerTransfers(),
                    unavailable.containerWithdrawals(), unavailable.categoryZones(), List.of(
                            factory(ContainerCacheRefresh.MODULE, context -> refresh),
                            factory("EAT_FOOD", context -> new TaskModuleExecutor() {
                                @Override
                                public TaskModuleResult start() {
                                    mealStarts++;
                                    return TaskModuleResult.waiting("eating");
                                }

                                @Override
                                public TaskModuleResult tick() {
                                    if (!mealDone) return TaskModuleResult.waiting("eating");
                                    return mealFailed ? TaskModuleResult.failed("no food")
                                            : TaskModuleResult.succeeded("ate");
                                }

                                @Override
                                public void cancel() {
                                    mealDone = true;
                                }
                            })));
            manager = new TaskRunManager(navigation, registry);
        }

        private TaskRunSnapshot begin() {
            var run = manager.start(task, config, null);
            manager.tick();
            return run;
        }

        private void advance() {
            for (int tick = 0; tick < 8; tick++) manager.tick();
        }

        private TaskRunState state(UUID id) {
            return manager.runs().stream().filter(run -> run.runId().equals(id))
                    .findFirst().orElseThrow().state();
        }
    }

    private static final class PausableRefresh implements TaskModuleExecutor {
        private boolean confirmed;
        private boolean pauseRequested;
        private boolean paused;
        private boolean stopped;
        private boolean complete;
        private boolean failed;
        private int starts;
        private int resumes;

        @Override
        public TaskModuleResult start() {
            starts++;
            return TaskModuleResult.waiting("reading");
        }

        @Override
        public TaskModuleResult tick() {
            if (failed) return TaskModuleResult.failed("world unavailable");
            if (stopped || complete) return TaskModuleResult.succeeded("finished");
            paused = pauseRequested && confirmed;
            return TaskModuleResult.waiting(paused ? "safe" : "reading");
        }

        @Override
        public void requestPauseAtSafeNode() {
            pauseRequested = true;
        }

        @Override
        public boolean pausedAtSafeNode() {
            return paused;
        }

        @Override
        public void resumeFromPause() {
            pauseRequested = false;
            paused = false;
            resumes++;
        }

        @Override
        public void requestStop() {
            stopped = true;
        }

        @Override
        public void cancel() {
            stopped = true;
        }
    }

    private static TaskModuleRuntimeFactory factory(String module,
            Function<TaskExecutionContext, TaskModuleExecutor> create) {
        return new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return module;
            }

            @Override
            public TaskModuleExecutor create(TaskStep step, TaskExecutionContext context) {
                return create.apply(context);
            }
        };
    }

    private static final class TestNavigation implements NavigationPort {
        private StopOutcome outcome = StopOutcome.CLEAN;

        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.ARRIVED;
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
            return outcome;
        }

        @Override
        public StopOutcome pollStop() {
            return outcome;
        }
    }
}
