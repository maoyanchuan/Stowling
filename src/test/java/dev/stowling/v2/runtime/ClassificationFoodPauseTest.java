package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoEatOptions;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeFactory;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ClassificationFoodPauseTest {
    @Test
    void finishesTransferThenResumesSameRunAndLedgerBeforeFreshNavigation() {
        var fixture = new Fixture(true, true);
        var run = fixture.beginTransfer();
        var workspace = fixture.workspace;
        var meal = fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.manager.tick();
        assertFalse(fixture.events.contains("meal"));
        assertFalse(fixture.events.contains("move:destination"));

        fixture.transferConfirmed = true;
        fixture.manager.tick();
        assertEquals(meal.runId(), fixture.manager.status().orElseThrow().runId());
        assertEquals(Map.of("minecraft:stone", 64), workspace.managedItems());
        assertEquals(run.runId(), fixture.manager.pendingTask(fixture.task.id())
                .orElseThrow().runId());
        assertTrue(fixture.manager.queued().stream().anyMatch(pending ->
                pending.runId().equals(run.runId()) && pending.state() == TaskRunState.WAITING));
        assertEquals(run.runId(), fixture.manager.start(fixture.task, fixture.config, null).runId());

        fixture.manager.tick();
        assertEquals(run.runId(), fixture.manager.status().orElseThrow().runId());
        assertSame(workspace, fixture.workspace);
        assertEquals(0, fixture.cleanups);
        fixture.manager.tick();
        assertTrue(fixture.events.indexOf("meal")
                < fixture.events.indexOf("move:destination"));
        fixture.manager.tick();
        assertEquals(1, fixture.events.stream().filter("withdraw"::equals).count());
        assertEquals(1, fixture.events.stream().filter("deposit"::equals).count());
        assertEquals(TaskRunState.SUCCEEDED, fixture.state(run.runId()));
        assertTrue(workspace.managedItems().isEmpty());
    }

    @Test
    void cancellationWhileEatingOnlyRunsCleanupWithoutResumingTransfers() {
        var fixture = new Fixture(true, true);
        var run = fixture.beginTransfer();
        fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.transferConfirmed = true;
        fixture.manager.tick();
        fixture.manager.cancelTask(fixture.task.id());
        for (int tick = 0; tick < 8; tick++) fixture.manager.tick();
        assertEquals(TaskRunState.CANCELLED, fixture.state(run.runId()));
        assertFalse(fixture.events.contains("move:destination"));
        assertFalse(fixture.events.contains("deposit"));
        assertEquals(1, fixture.cleanups);
    }

    @Test
    void disabledExperimentAndUnrelatedTasksKeepOriginalPreemption() {
        for (var fixture : List.of(new Fixture(false, true), new Fixture(true, false))) {
            var run = fixture.beginTransfer();
            fixture.manager.start(fixture.meal, fixture.config, null);
            for (int tick = 0; tick < 8; tick++) fixture.manager.tick();
            assertEquals(TaskRunState.PREEMPTED, fixture.state(run.runId()));
            assertFalse(fixture.events.contains("move:destination"));
        }
    }

    @Test
    void cancellingQueuedMealBeforeBoundaryLetsClassificationContinue() {
        var fixture = new Fixture(true, true);
        var run = fixture.beginTransfer();
        var meal = fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.manager.cancel(meal.runId());
        fixture.transferConfirmed = true;
        for (int tick = 0; tick < 5; tick++) fixture.manager.tick();
        assertEquals(TaskRunState.SUCCEEDED, fixture.state(run.runId()));
        assertFalse(fixture.events.contains("meal"));
    }

    @Test
    void waitsForNavigationStopConfirmationBeforeGivingEatingTheSlot() {
        var fixture = new Fixture(true, true);
        fixture.beginTransfer();
        var meal = fixture.manager.start(fixture.meal, fixture.config, null);
        fixture.transferConfirmed = true;
        fixture.navigation.stopOutcome = StopOutcome.PENDING;
        fixture.manager.tick();
        assertFalse(fixture.events.contains("meal"));
        assertEquals(TaskRunState.QUEUED, fixture.state(meal.runId()));
        fixture.navigation.stopOutcome = StopOutcome.CLEAN;
        fixture.manager.tick();
        assertEquals(meal.runId(), fixture.manager.status().orElseThrow().runId());
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final TestNavigation navigation = new TestNavigation();
        private final TaskRunManager manager;
        private final TaskDefinition task;
        private final TaskDefinition meal;
        private final AppConfig config;
        private Map<String, Integer> actual = Map.of();
        private TaskRunWorkspace workspace;
        private boolean transferConfirmed;
        private int cleanups;

        private Fixture(boolean enabled, boolean classification) {
            var source = new RegionDefinition(UUID.randomUUID(), "Source", 0, 64, 0, 1, 64, 0);
            var definition = new Gson().fromJson("""
                    {"id":"%s","name":"Sort","kind":"CLASSIFICATION",
                     "cacheLocationRef":"region:%s",
                     "classificationAreas":[{"categoryZoneRef":"category-zone:%s"}],
                     "collectionPriority":"NORMAL","foodPauseResumeEnabled":%s}
                    """.formatted(UUID.randomUUID(), source.id(), UUID.randomUUID(), enabled),
                    AutoClassifyDefinition.class);
            task = new TaskDefinition(classification ? definition.id() : UUID.randomUUID(),
                    "Sort", TaskPriority.BACKGROUND, false,
                    List.of(move("source"), step("WITHDRAW_ITEMS"), move("destination"),
                            step("DEPOSIT_ITEMS_TO_CONTAINERS")), null, null, false, false,
                    null, definition.cleanupPolicy());
            meal = new TaskDefinition(UUID.randomUUID(), "Eat", TaskPriority.URGENT, false,
                    List.of(step("EAT_FOOD")), null, null, false, false, null, null,
                    new AutoEatOptions(false, null, 64, true));
            config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                    List.of(task, meal), List.of(source), List.of(), List.of(), List.of(),
                    List.of(definition));
            var unavailable = TaskModuleRuntimeRegistry.unavailablePorts(navigation);
            var observer = new DroppedItemObserverPort() {
                @Override
                public List<DroppedItem> observe(BlockPosition center, int radius) {
                    return List.of();
                }

                @Override
                public List<DroppedItem> observe(RegionDefinition region) {
                    return List.of();
                }

                @Override
                public Optional<Map<String, Integer>> playerItemCounts() {
                    return Optional.of(actual);
                }
            };
            var registry = new TaskModuleRuntimeRegistry(navigation, observer,
                    unavailable.containerRegions(), unavailable.containerTransfers(),
                    unavailable.containerWithdrawals(), unavailable.categoryZones(), List.of(
                            factory("MOVE_TO_COORDINATE", this::movement),
                            factory("WITHDRAW_ITEMS", context -> new TaskModuleExecutor() {
                                @Override
                                public TaskModuleResult start() {
                                    workspace = context.workspace();
                                    return TaskModuleResult.waiting("transfer pending");
                                }

                                @Override
                                public TaskModuleResult tick() {
                                    if (!transferConfirmed) return start();
                                    actual = Map.of("minecraft:stone", 64);
                                    workspace.recordWithdrawn("minecraft:stone", 64);
                                    events.add("withdraw");
                                    return TaskModuleResult.succeeded("transfer confirmed");
                                }

                                @Override
                                public void cancel() {
                                    events.add("withdraw cancelled");
                                }
                            }),
                            factory("DEPOSIT_ITEMS_TO_CONTAINERS", context -> instant(() -> {
                                assertSame(workspace, context.workspace());
                                events.add("deposit");
                                clearCargo(context);
                            })),
                            factory("EAT_FOOD", context -> instant(() -> events.add("meal"))),
                            factory("GLOBAL_INVENTORY_CLEANUP", context -> instant(() -> {
                                cleanups++;
                                clearCargo(context);
                            }))));
            manager = new TaskRunManager(navigation, registry);
        }

        private TaskModuleExecutor movement(TaskExecutionContext context) {
            return new TaskModuleExecutor() {
                @Override
                public TaskModuleResult start() {
                    events.add("move:" + (workspace == null ? "source" : "destination"));
                    return TaskModuleResult.running("moving");
                }

                @Override
                public TaskModuleResult tick() {
                    return TaskModuleResult.succeeded("arrived");
                }

                @Override
                public void cancel() {
                }
            };
        }

        private void clearCargo(TaskExecutionContext context) {
            context.workspace().consumeDeposit("carried", context.workspace().carriedItems(),
                    Map.of());
            actual = Map.of();
        }

        private TaskRunSnapshot beginTransfer() {
            var run = manager.start(task, config, null);
            manager.tick();
            manager.tick();
            return run;
        }

        private TaskRunState state(UUID id) {
            return manager.runs().stream().filter(run -> run.runId().equals(id))
                    .findFirst().orElseThrow().state();
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

    private static TaskModuleExecutor instant(Runnable effect) {
        return new TaskModuleExecutor() {
            @Override
            public TaskModuleResult start() {
                effect.run();
                return TaskModuleResult.succeeded("done");
            }

            @Override
            public TaskModuleResult tick() {
                return start();
            }

            @Override
            public void cancel() {
            }
        };
    }

    private static TaskStep step(String module) {
        return TaskStep.action(UUID.randomUUID(), module, Map.of());
    }

    private static TaskStep move(String destination) {
        return TaskStep.action(UUID.randomUUID(), "MOVE_TO_COORDINATE",
                Map.of("destination", destination));
    }

    private static final class TestNavigation implements NavigationPort {
        private StopOutcome stopOutcome = StopOutcome.CLEAN;

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
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop() {
            return stopOutcome;
        }
    }
}
