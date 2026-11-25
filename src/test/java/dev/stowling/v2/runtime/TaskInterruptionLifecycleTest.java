package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerRecoveryDrop;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskCleanupRouting;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.runtime.modules.StandardTaskModuleFactories;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeFactory;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskInterruptionLifecycleTest {
    @Test
    void continueModeRetainsConfirmedDeliveryAndDoesNotRepeatCompletedStages() {
        var point = coordinate(UUID.randomUUID());
        String destination = "coordinate:" + point.id();
        BlockPosition position = new BlockPosition(point.x(), point.y(), point.z());
        AtomicInteger firstStage = new AtomicInteger();
        AtomicInteger workStarts = new AtomicInteger();
        AtomicInteger gathered = new AtomicInteger();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskModuleRuntimeFactory stage = new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return "TEST_GATHER_PROGRESS";
            }

            @Override
            public TaskModuleExecutor create(TaskStep step, TaskExecutionContext context) {
                return new TestAction(context) {
                    @Override
                    public TaskModuleResult start() {
                        if ("first".equals(step.parameters().get("stage"))) {
                            firstStage.incrementAndGet();
                            return TaskModuleResult.succeeded("completed first stage");
                        }
                        int needed = context.workspace().progress().remainingAt(position,
                                Map.of("minecraft:stone", 1000)).get("minecraft:stone");
                        if (workStarts.incrementAndGet() == 1) {
                            gathered.addAndGet(400);
                            context.workspace().recordManagedAcquired("minecraft:stone", 400);
                            return TaskModuleResult.waiting("partially gathered");
                        }
                        assertEquals(600, needed);
                        gathered.addAndGet(needed);
                        context.workspace().progress().recordDelivery(position,
                                Map.of("minecraft:stone", needed));
                        return TaskModuleResult.succeeded("remaining materials delivered");
                    }
                };
            }
        };
        var registry = registry(navigation, stage);
        registry.setStorageAutomation(new dev.stowling.v2.runtime.modules.StorageAutomationPort() {
            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new AssertionError("unused");
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                assertEquals(destination, context.workspace().cleanupPolicy().destinationRef());
                return new TestAction(context) {
                    @Override
                    public TaskModuleResult start() {
                        var items = context.workspace().managedItems();
                        context.workspace().progress().recordDelivery(position, items);
                        context.workspace().consumeDeposit("managed", items, Map.of());
                        return TaskModuleResult.succeeded("delivered during interruption");
                    }
                };
            }
        });
        var policy = new dev.stowling.v2.model.TaskCleanupPolicy(destination,
                dev.stowling.v2.model.TaskResumeMode.CONTINUE);
        TaskDefinition background = new TaskDefinition(UUID.randomUUID(), "Gather",
                TaskPriority.BACKGROUND, false, List.of(
                        TaskStep.action(UUID.randomUUID(), stage.moduleId(), Map.of("stage", "first")),
                        TaskStep.action(UUID.randomUUID(), stage.moduleId(), Map.of())),
                TaskInterruptionMode.NONE, null, false, false, null, policy);
        TaskDefinition urgent = task("Pickup", TaskPriority.URGENT,
                TaskInterruptionMode.NONE, actionStep(NOOP));
        AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                List.of(background, urgent));
        TaskRunManager manager = new TaskRunManager(navigation, registry);
        manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 40; tick++) manager.tick();
        assertEquals(1, firstStage.get());
        assertEquals(2, workStarts.get());
        assertEquals(1000, gathered.get());
        assertTrue(manager.runs().stream().anyMatch(run -> run.taskId().equals(background.id())
                && run.state() == TaskRunState.SUCCEEDED));
    }

    @Test
    void newCleanupRoutingSurvivesPreemptionAndCancellationWithItsTaskSnapshot() {
        for (boolean cancel : List.of(false, true)) {
            for (boolean mixed : List.of(false, true)) {
                RecordingNavigation navigation = new RecordingNavigation();
                TaskModuleRuntimeRegistry registry = registry(navigation);
                AtomicReference<TaskCleanupRouting> received = new AtomicReference<>();
                var overflow = new RegionDefinition(UUID.randomUUID(), "Overflow",
                        10, 64, 0, 11, 64, 0);
                var warehouse = new RegionDefinition(UUID.randomUUID(), "Robot",
                        20, 64, 0, 21, 64, 0);
                var cache = new RegionDefinition(UUID.randomUUID(), "Cache",
                        0, 64, 0, 1, 64, 0);
                String overflowRef = "region:" + overflow.id();
                String robotRef = "region:" + warehouse.id();
                registry.setStorageAutomation(
                        new dev.stowling.v2.runtime.modules.StorageAutomationPort() {
                            @Override
                            public boolean cleanupEnabled() {
                                return true;
                            }

                            @Override
                            public TaskModuleExecutor deposit(TaskExecutionContext context,
                                    ContainerTarget target, Map<String, Integer> items) {
                                throw new AssertionError("unused");
                            }

                            @Override
                            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                                throw new AssertionError("must preserve new cleanup routing");
                            }

                            @Override
                            public TaskModuleExecutor cleanup(TaskExecutionContext context,
                                    TaskCleanupRouting routing) {
                                received.set(routing);
                                assertEquals(Map.of("minecraft:iron_ingot", 1),
                                        context.workspace().managedItems());
                                return new TestAction(context) {
                                    @Override
                                    public TaskModuleResult start() {
                                        context.workspace().consumeDeposit("managed",
                                                context.workspace().managedItems(), Map.of());
                                        return TaskModuleResult.succeeded("cleaned");
                                    }
                                };
                            }
                        });
                var direct = TaskStep.action(UUID.randomUUID(), "ROUTE_CLASSIFICATION_REMAINDERS",
                        Map.of("cacheLocationRef", "region:" + cache.id(), "targetRef", overflowRef,
                                "remainderKind", "OVERFLOW"));
                var robot = TaskStep.action(UUID.randomUUID(), "ROBOT_WAREHOUSE",
                        Map.of("targetRef", robotRef, "mixedBoxes", Boolean.toString(mixed)));
                TaskDefinition background = task("Classify", TaskPriority.BACKGROUND,
                        TaskInterruptionMode.NONE, actionStep(ADD_MANAGED), actionStep(SLOW),
                        direct, robot);
                TaskDefinition urgent = task("Pickup", TaskPriority.URGENT,
                        TaskInterruptionMode.NONE, actionStep(NOOP));
                AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                        List.of(background, urgent), List.of(cache, overflow, warehouse), List.of(),
                        List.of());
                TaskRunManager manager = new TaskRunManager(navigation, registry);
                TaskRunSnapshot run = manager.start(background, config, null);
                manager.tick();
                // Later configuration changes must not replace the active run's choices.
                var changed = new TaskDefinition(background.id(), "Updated",
                        List.of(actionStep(NOOP)));
                manager.refreshTriggers(new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                        List.of(), List.of(changed, urgent)));
                if (cancel) manager.cancelTask(background.id());
                else manager.start(urgent, config, null);
                for (int tick = 0; tick < 24; tick++) manager.tick();

                assertEquals(new TaskCleanupRouting(false,
                        dev.stowling.v2.model.ResourceReference.parse(overflowRef),
                        dev.stowling.v2.model.ResourceReference.parse(robotRef), mixed),
                        received.get());
                assertEquals(cancel ? TaskRunState.CANCELLED : TaskRunState.PREEMPTED,
                        runState(manager, run.runId()));
            }
        }
    }

    @Test
    void urgentPreemptionPreservesTheInterruptedTasksOverflowChoiceDuringGlobalCleanup() {
        for (boolean enabled : List.of(false, true)) {
            RecordingNavigation navigation = new RecordingNavigation();
            TaskModuleRuntimeRegistry registry = registry(navigation);
            AtomicReference<Boolean> received = new AtomicReference<>();
            registry.setStorageAutomation(
                    new dev.stowling.v2.runtime.modules.StorageAutomationPort() {
                        @Override
                        public boolean cleanupEnabled() {
                            return true;
                        }

                        @Override
                        public TaskModuleExecutor deposit(TaskExecutionContext context,
                                ContainerTarget target, Map<String, Integer> items) {
                            throw new AssertionError("cleanup uses its own storage operation");
                        }

                        @Override
                        public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                            throw new AssertionError("cleanup must receive the overflow choice");
                        }

                        @Override
                        public TaskModuleExecutor cleanup(TaskExecutionContext context,
                                boolean overflowEnabled) {
                            received.set(overflowEnabled);
                            return new TestAction(context) {
                                @Override
                                public TaskModuleResult start() {
                                    context.workspace().consumeDeposit("managed",
                                            context.workspace().managedItems(), Map.of());
                                    return TaskModuleResult.succeeded("cleaned");
                                }
                            };
                        }
                    });
            TaskDefinition background = task("Classify", TaskPriority.BACKGROUND,
                    TaskInterruptionMode.NONE, actionStep(ADD_MANAGED), actionStep(SLOW),
                    actionStep(enabled ? "PACK_OVERFLOW" : NOOP));
            TaskDefinition urgent = task("Pickup", TaskPriority.URGENT,
                    TaskInterruptionMode.NONE, actionStep(NOOP));
            AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                    List.of(), List.of(background, urgent));
            TaskRunManager manager = new TaskRunManager(navigation, registry);
            TaskRunSnapshot run = manager.start(background, config, null);
            manager.tick();
            manager.start(urgent, config, null);
            for (int tick = 0; tick < 24; tick++) manager.tick();
            assertEquals(Boolean.valueOf(enabled), received.get());
            assertEquals(TaskRunState.PREEMPTED, runState(manager, run.runId()));
        }
    }

    @Test
    void recoveringABoxDoesNotPreemptClassificationButForeignDropsStillDo() {
        String box = "minecraft:shulker_box#recovering@minecraft:stone";
        BlockPosition site = new BlockPosition(10, 64, 10);
        DroppedItem ownedBox = new DroppedItem(UUID.randomUUID(), box, 1, site);
        AtomicReference<List<DroppedItem>> observed = new AtomicReference<>(List.of());
        DroppedItemObserverPort drops = new DroppedItemObserverPort() {
            @Override
            public List<DroppedItem> observe(BlockPosition center, int radius) {
                return observed.get();
            }

            @Override
            public List<DroppedItem> observe(RegionDefinition region) {
                return observed.get();
            }
        };
        RecordingNavigation navigation = new RecordingNavigation();
        TaskModuleRuntimeRegistry registry = registryWithDrops(navigation, drops);
        AtomicInteger cleaned = new AtomicInteger();
        registry.setStorageAutomation(new dev.stowling.v2.runtime.modules.StorageAutomationPort() {
            @Override
            public boolean cleanupEnabled() {
                return true;
            }

            @Override
            public boolean isRecoveryDrop(DroppedItem item) {
                return ContainerRecoveryDrop.matches(item, site, box);
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                return new TestAction(context) {
                    @Override
                    public TaskModuleResult start() {
                        cleaned.incrementAndGet();
                        context.workspace().consumeDeposit("managed",
                                context.workspace().managedItems(), Map.of());
                        return TaskModuleResult.succeeded("cleaned");
                    }
                };
            }

            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new UnsupportedOperationException();
            }
        });
        TaskDefinition classify = task("Classify", TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE, actionStep(ADD_MANAGED), actionStep(SLOW));
        TaskDefinition pickup = task("Pickup", TaskPriority.URGENT,
                TaskInterruptionMode.NONE, actionStep(NOOP));
        UUID coordinateId = UUID.randomUUID();
        var trigger = TaskTriggerDefinition.droppedItems(UUID.randomUUID(), "Drops", pickup.id(),
                TaskPriority.URGENT, 1, 0, coordinateId, null);
        AppConfig config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(coordinate(coordinateId)), List.of(classify, pickup), List.of(), List.of(),
                List.of(trigger));
        TaskRunManager manager = new TaskRunManager(navigation, registry);
        manager.refreshTriggers(config);
        TaskRunSnapshot run = manager.start(classify, config, null);
        manager.tick();
        observed.set(List.of(ownedBox));
        for (int tick = 0; tick < 3; tick++) {
            manager.tick();
        }
        assertEquals(run.runId(), manager.status().orElseThrow().runId());
        assertEquals(TaskRunState.WAITING, manager.status().orElseThrow().state());
        assertTrue(manager.queued().isEmpty());
        assertEquals(0, cleaned.get());

        observed.set(List.of(ownedBox,
                new DroppedItem(UUID.randomUUID(), "minecraft:dirt", 1, site)));
        manager.tick();
        assertTrue(manager.runs().stream().anyMatch(snapshot -> snapshot.taskId().equals(pickup.id())));
    }

    @Test
    void globalCleanupAppliesWithoutAnyTaskSpecificInterruptionFlow() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskModuleRuntimeRegistry registry = registry(navigation);
        AtomicInteger cleaned = new AtomicInteger();
        registry.setStorageAutomation(
                new dev.stowling.v2.runtime.modules.StorageAutomationPort() {
                    @Override
                    public boolean cleanupEnabled() {
                        return true;
                    }

                    @Override
                    public TaskModuleExecutor deposit(TaskExecutionContext context,
                            ContainerTarget target, Map<String, Integer> items) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                        return new TestAction(context) {
                            @Override
                            public TaskModuleResult start() {
                                cleaned.incrementAndGet();
                                context.workspace().consumeDeposit("managed",
                                        context.workspace().managedItems(), Map.of());
                                return TaskModuleResult.succeeded("returned to temporary storage");
                            }
                        };
                    }
                });
        TaskRunManager manager = new TaskRunManager(navigation, registry);
        TaskDefinition task = task("Independent", TaskPriority.NORMAL,
                TaskInterruptionMode.NONE, actionStep(ADD_MANAGED), actionStep(SLOW));
        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();
        manager.cancel(run.runId());
        for (int tick = 0; tick < 12; tick++) {
            manager.tick();
        }
        assertEquals(1, cleaned.get());
        assertEquals(TaskRunState.CANCELLED, runState(manager, run.runId()));
    }

    private static final String ADD_MANAGED = "TEST_ADD_MANAGED";
    private static final String CLEAR_MANAGED = "TEST_CLEAR_MANAGED";
    private static final String SLOW = "TEST_SLOW";
    private static final String NOOP = "TEST_NOOP";
    private static final String FLAKY_CLEANUP = "TEST_FLAKY_CLEANUP";

    @Test
    void createTaskDeduplicatesIndependentRunsAndKeepsTargetPriority() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition child = task(
                "Child",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                moveStep(coordinateId)
        );
        TaskDefinition parent = task(
                "Parent",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of("taskRef", "task:" + child.id())
                ),
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of("taskRef", "task:" + child.id())
                ),
                actionStep(SLOW)
        );
        AppConfig config = config(
                List.of(coordinate(coordinateId)),
                List.of(parent, child)
        );
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );

        TaskRunSnapshot parentRun = manager.start(parent, config, null);
        manager.tick();

        assertEquals(1, manager.queued().size());
        assertTrue(manager.queued().stream().allMatch(
                run -> run.priority() == TaskPriority.BACKGROUND
        ));
        TaskRunSnapshot childRun = manager.queued().getFirst();
        assertEquals(child.id(), childRun.taskId());

        manager.cancel(parentRun.runId());
        manager.tick();

        assertEquals(
                child.id(),
                manager.status().orElseThrow().taskId()
        );
    }


    @Test
    void createdHighPriorityTaskRequestsInterruptionWithoutNesting() {
        UUID coordinateId = UUID.randomUUID();
        TaskDefinition child = task(
                "Created urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(coordinateId)
        );
        TaskDefinition parent = task(
                "Parent",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                TaskStep.createTask(
                        UUID.randomUUID(),
                        Map.of("taskRef", "task:" + child.id())
                ),
                actionStep(SLOW)
        );
        AppConfig config = config(
                List.of(coordinate(coordinateId)),
                List.of(parent, child)
        );
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );

        TaskRunSnapshot parentRun = manager.start(parent, config, null);
        manager.tick();

        assertEquals(
                TaskRunState.INTERRUPTING,
                runState(manager, parentRun.runId())
        );
        assertEquals(
                TaskPriority.URGENT,
                manager.queued().getFirst().priority()
        );
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }
        assertTrue(manager.runs().stream().anyMatch(
                run -> run.taskId().equals(child.id())
                        && run.state() == TaskRunState.SUCCEEDED
        ));
    }

    @Test
    void preemptionRunsCustomCleanupBeforeUrgentTask() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(CLEAR_MANAGED),
                moveStep(cleanupPoint)
        );
        TaskDefinition background = task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );
        background = withCleanup(background, cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(
                        coordinate(cleanupPoint, 10),
                        coordinate(urgentPoint, 20)
                ),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot backgroundRun = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 8; tick++) {
            manager.tick();
        }

        assertEquals(
                List.of(
                        new BlockPosition(10, 64, 10),
                        new BlockPosition(20, 64, 10)
                ),
                navigation.positions()
        );
        assertEquals(
                TaskRunState.PREEMPTED,
                runState(manager, backgroundRun.runId())
        );
        assertTrue(manager.runs().stream().anyMatch(
                run -> run.taskName().equals("Urgent")
                        && run.state() == TaskRunState.SUCCEEDED
        ));
    }

    @Test
    void preemptionWithoutManagedItemsSkipsCustomCleanup() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                moveStep(cleanupPoint)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(
                        coordinate(cleanupPoint, 10),
                        coordinate(urgentPoint, 20)
                ),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot backgroundRun = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(
                List.of(new BlockPosition(20, 64, 10)),
                navigation.positions()
        );
        assertEquals(
                TaskRunState.PREEMPTED,
                runState(manager, backgroundRun.runId())
        );
        assertTrue(manager.runs().stream().anyMatch(
                run -> run.taskName().equals("Urgent")
                        && run.state() == TaskRunState.SUCCEEDED
        ));
    }

    @Test
    void preemptedBackgroundTaskRequeuesAfterSharedCleanup() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Shared cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(CLEAR_MANAGED)
        );
        TaskDefinition background = withCleanup(task(
                "Classifier",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, true);
        TaskDefinition urgent = task(
                "Collector",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(
                        coordinate(cleanupPoint),
                        coordinate(urgentPoint)
                ),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot backgroundRun = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 8; tick++) {
            manager.tick();
        }

        assertEquals(
                TaskRunState.PREEMPTED,
                runState(manager, backgroundRun.runId())
        );
        assertTrue(manager.hasPendingTask(background.id()));
        assertTrue(manager.queued().stream()
                .filter(run -> run.taskId().equals(background.id()))
                .count() <= 1);
    }

    @Test
    void defaultCleanupRunsAfterCancellation() {
        UUID cleanupPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = new TaskDefinition(
                UUID.randomUUID(),
                "Default Cleanup",
                TaskPriority.BACKGROUND,
                false,
                List.of(actionStep(CLEAR_MANAGED), moveStep(cleanupPoint)),
                TaskInterruptionMode.NONE,
                null,
                true,
                false
        );
        TaskDefinition running = task(
                "Running",
                TaskPriority.NORMAL,
                TaskInterruptionMode.DEFAULT,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );
        AppConfig config = config(
                List.of(coordinate(cleanupPoint)),
                List.of(running, cleanup)
        );

        TaskRunSnapshot run = manager.start(running, config, null);
        manager.tick();
        manager.cancel(run.runId());
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(TaskRunState.CANCELLED, runState(manager, run.runId()));
        assertEquals(
                List.of(new BlockPosition(10, 64, 10)),
                navigation.positions()
        );
    }

    @Test
    void cleanupFailureKeepsTheSlotAndDoesNotStartQueuedTask() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.failAt(new BlockPosition(10, 64, 10));
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(NOOP),
                moveStep(cleanupPoint)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(coordinate(cleanupPoint), coordinate(urgentPoint)),
                List.of(background, cleanup, urgent)
        );

        manager.start(background, config, null);
        manager.tick();
        TaskRunSnapshot urgentRun = manager.start(urgent, config, null);
        for (int tick = 0; tick < 8; tick++) {
            manager.tick();
        }

        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );
        assertTrue(manager.busy());
        assertEquals(
                urgentRun.runId(),
                manager.queued().getFirst().runId()
        );
        assertTrue(manager.status().orElseThrow().message().contains("原任务停止原因"));
        assertTrue(manager.status().orElseThrow().message().contains("Urgent"));
    }


    @Test
    void cleanupFailureRequestsStopForNewCleanupNavigation() {
        UUID cleanupPoint = UUID.randomUUID();
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.stopOutcomes(
                StopOutcome.PENDING,
                StopOutcome.CLEAN,
                StopOutcome.CLEAN
        );
        navigation.failAt(new BlockPosition(10, 64, 10));
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                moveStep(cleanupPoint)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(
                        coordinate(cleanupPoint, 10),
                        coordinate(urgentPoint, 20)
                ),
                List.of(background, cleanup, urgent)
        );

        manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );
        assertEquals(2, navigation.requestStopCount());
        assertEquals(1, navigation.pollStopCount());
    }

    @Test
    void retryReconfirmsNavigationStopBeforeCleanup() {
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.stopOutcomes(StopOutcome.UNSAFE, StopOutcome.CLEAN);
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(CLEAR_MANAGED)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(coordinate(urgentPoint)),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot run = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        manager.tick();
        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );
        assertThrows(
                IllegalStateException.class,
                () -> manager.skipInterruption(run.runId())
        );

        manager.retryInterruption(run.runId());
        manager.tick();
        manager.tick();
        manager.tick();

        assertEquals(
                TaskRunState.PREEMPTED,
                runState(manager, run.runId())
        );
        assertTrue(manager.runs().stream().anyMatch(
                candidate -> candidate.taskId().equals(urgent.id())
        ));
    }



    @Test
    void forceStopAbandonsInFlightCleanupAndResumesTheQueueAfterNavigationStops() {
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.stopOutcomes(StopOutcome.CLEAN, StopOutcome.PENDING, StopOutcome.CLEAN);
        AtomicReference<Map<String, Integer>> backpack = new AtomicReference<>(Map.of());
        var drops = new EmptyDroppedItems() {
            @Override
            public Optional<Map<String, Integer>> playerItemCounts() {
                return Optional.of(backpack.get());
            }
        };
        AtomicInteger cleanupStarts = new AtomicInteger();
        AtomicInteger cleanupCancels = new AtomicInteger();
        TaskModuleRuntimeFactory stuckCleanup = new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return FLAKY_CLEANUP;
            }

            @Override
            public TaskModuleExecutor create(TaskStep step, TaskExecutionContext context) {
                return new TestAction(context) {
                    @Override
                    public TaskModuleResult start() {
                        cleanupStarts.incrementAndGet();
                        return TaskModuleResult.waiting("awaiting transfer");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        throw new AssertionError("force stop must not tick the cleanup action");
                    }

                    @Override
                    public boolean safeToStop() {
                        return false;
                    }

                    @Override
                    public void cancel() {
                        cleanupCancels.incrementAndGet();
                    }
                };
            }
        };
        var registry = registryWithDrops(navigation, drops, stuckCleanup);
        var manager = new TaskRunManager(navigation, registry);
        var cleanup = task("Cleanup", TaskPriority.NORMAL, TaskInterruptionMode.NONE,
                actionStep(FLAKY_CLEANUP));
        var background = withCleanup(task("Background", TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE, actionStep(ADD_MANAGED), actionStep(SLOW)),
                cleanup, true);
        var urgent = task("Urgent", TaskPriority.URGENT, TaskInterruptionMode.NONE,
                actionStep(NOOP));
        AppConfig config = config(List.of(), List.of(background, cleanup, urgent));
        var run = manager.start(background, config, null);
        manager.tick();
        backpack.set(Map.of("minecraft:iron_ingot", 1));
        var next = manager.start(urgent, config, null);
        manager.tick();
        manager.tick();
        assertEquals(1, cleanupStarts.get());

        backpack.set(Map.of());
        manager.forceStop(run.runId());
        assertEquals(0, cleanupCancels.get());
        manager.tick();
        assertEquals(1, cleanupCancels.get());
        assertEquals(TaskRunState.INTERRUPTING, runState(manager, run.runId()));
        assertEquals(TaskRunState.QUEUED, runState(manager, next.runId()));

        manager.forceStop(run.runId());
        manager.tick();
        assertEquals(TaskRunState.CANCELLED, runState(manager, run.runId()));
        assertEquals(TaskRunState.RUNNING, runState(manager, next.runId()));
        assertEquals(1, cleanupCancels.get());
        assertEquals(1, navigation.pollStopCount());
        assertFalse(registry.inventory().hasAbandonedAccounts());
        assertTrue(manager.queued().isEmpty());
    }

    @Test
    void forceStopFromInterruptionFailureReconfirmsNavigationAndCancelsTheRun() {
        RecordingNavigation navigation = new RecordingNavigation();
        navigation.stopOutcomes(StopOutcome.UNSAFE, StopOutcome.UNSAFE, StopOutcome.CLEAN);
        var manager = new TaskRunManager(navigation, registry(navigation));
        var task = task("Wait", TaskPriority.NORMAL, TaskInterruptionMode.NONE, actionStep(SLOW));
        var run = manager.start(task, List.of());
        manager.tick();
        manager.cancel(run.runId());
        manager.tick();
        assertEquals(TaskRunState.INTERRUPTION_FAILED, runState(manager, run.runId()));

        manager.forceStop(run.runId());
        manager.tick();
        assertEquals(TaskRunState.INTERRUPTION_FAILED, runState(manager, run.runId()));

        manager.forceStop(run.runId());
        manager.tick();
        assertEquals(TaskRunState.CANCELLED, runState(manager, run.runId()));
        assertEquals(3, navigation.requestStopCount());
    }

    @Test
    void retryDrainsInFlightCleanupBeforeStartingItAgain() {
        UUID urgentPoint = UUID.randomUUID();
        RecordingNavigation navigation = new RecordingNavigation();
        AtomicInteger attempts = new AtomicInteger();
        TaskModuleRuntimeFactory flaky = new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return FLAKY_CLEANUP;
            }

            @Override
            public TaskModuleExecutor create(
                    TaskStep step,
                    TaskExecutionContext context
            ) {
                int attempt = attempts.incrementAndGet();
                return new TestAction(context) {
                    private boolean stopping;
                    private boolean terminal;

                    @Override
                    public TaskModuleResult start() {
                        if (attempt == 1) {
                            return TaskModuleResult.waiting("opening");
                        }
                        context.workspace().consumeManaged(
                                "minecraft:iron_ingot",
                                1
                        );
                        terminal = true;
                        return TaskModuleResult.succeeded("cleaned");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        if (attempt > 1) {
                            terminal = true;
                            return TaskModuleResult.succeeded("cleaned");
                        }
                        if (stopping) {
                            terminal = true;
                            return TaskModuleResult.failed("drained failure");
                        }
                        throw new IllegalStateException("interruption error");
                    }

                    @Override
                    public void requestStop() {
                        stopping = true;
                    }

                    @Override
                    public boolean safeToStop() {
                        return terminal;
                    }
                };
            }
        };
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation, flaky)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(FLAKY_CLEANUP)
        );
        TaskDefinition background = withCleanup(task(
                "Background",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        ), cleanup, false);
        TaskDefinition urgent = task(
                "Urgent",
                TaskPriority.URGENT,
                TaskInterruptionMode.NONE,
                moveStep(urgentPoint)
        );
        AppConfig config = config(
                List.of(coordinate(urgentPoint)),
                List.of(background, cleanup, urgent)
        );

        TaskRunSnapshot run = manager.start(background, config, null);
        manager.tick();
        manager.start(urgent, config, null);
        manager.tick();
        manager.tick();
        manager.tick();
        manager.tick();
        assertEquals(
                TaskRunState.INTERRUPTION_FAILED,
                manager.status().orElseThrow().state()
        );

        manager.retryInterruption(run.runId());
        for (int tick = 0; tick < 5; tick++) {
            manager.tick();
        }

        assertEquals(TaskRunState.PREEMPTED, runState(manager, run.runId()));
        assertEquals(2, attempts.get());
    }

    @Test
    void unsafeInFlightStartFailureWaitsBeforeInterruption() {
        RecordingNavigation navigation = new RecordingNavigation();
        AtomicBoolean safeToStop = new AtomicBoolean();
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger requestStops = new AtomicInteger();
        TaskModuleRuntimeFactory unsafe = new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return "TEST_UNSAFE_START";
            }

            @Override
            public TaskModuleExecutor create(
                    TaskStep step,
                    TaskExecutionContext context
            ) {
                return new TestAction(context) {
                    @Override
                    public TaskModuleResult start() {
                        throw new IllegalStateException("start boom");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        throw new IllegalStateException("stop boom");
                    }

                    @Override
                    public void requestStop() {
                        requestStops.incrementAndGet();
                    }

                    @Override
                    public boolean safeToStop() {
                        return safeToStop.get();
                    }

                    @Override
                    public void cancel() {
                        cancels.incrementAndGet();
                    }
                };
            }
        };
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation, unsafe)
        );
        TaskDefinition cleanup = task(
                "Cleanup",
                TaskPriority.BACKGROUND,
                TaskInterruptionMode.NONE,
                actionStep(NOOP)
        );
        TaskDefinition background = withCleanup(task(
                "Unsafe",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                actionStep("TEST_UNSAFE_START")
        ), cleanup, false);
        AppConfig config = config(
                List.of(),
                List.of(background, cleanup)
        );

        TaskRunSnapshot run = manager.start(background, config, null);
        manager.tick();
        manager.tick();

        assertEquals(
                TaskRunState.INTERRUPTING,
                manager.status().orElseThrow().state()
        );
        assertEquals(1, requestStops.get());
        assertEquals(0, navigation.requestStopCount());
        assertEquals(0, cancels.get());

        safeToStop.set(true);
        manager.tick();
        manager.tick();

        TaskRunSnapshot failed = manager.runs().stream()
                .filter(candidate -> candidate.runId().equals(run.runId()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskRunState.FAILED, failed.state());
        assertEquals(0, failed.completedSteps());
        assertTrue(failed.message().contains("第 1 步（TEST_UNSAFE_START）"));
        assertEquals(1, navigation.requestStopCount());
        assertEquals(1, cancels.get());
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void cancellationWithNoCleanupFinishesAndReportsManagedResidue() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition task = task(
                "No cleanup",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );

        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();
        manager.cancel(run.runId());
        manager.tick();

        TaskRunSnapshot snapshot = manager.runs().stream()
                .filter(candidate -> candidate.runId().equals(run.runId()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskRunState.CANCELLED, snapshot.state());
        assertTrue(snapshot.message().contains("受管物品"));
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void missingDefaultCleanupDoesNotCreatePermanentFailure() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition task = task(
                "Default absent",
                TaskPriority.NORMAL,
                TaskInterruptionMode.DEFAULT,
                actionStep(ADD_MANAGED),
                actionStep(SLOW)
        );

        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();
        manager.cancel(run.runId());
        manager.tick();

        TaskRunSnapshot snapshot = manager.runs().stream()
                .filter(candidate -> candidate.runId().equals(run.runId()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskRunState.CANCELLED, snapshot.state());
        assertTrue(snapshot.message().contains("未执行清理"));
        assertTrue(manager.status().isEmpty());
    }

    @Test
    void ordinaryTaskSuccessDoesNotRequireEmptyManagedLedger() {
        RecordingNavigation navigation = new RecordingNavigation();
        TaskRunManager manager = new TaskRunManager(
                navigation,
                registry(navigation)
        );
        TaskDefinition task = task(
                "Single action",
                TaskPriority.NORMAL,
                TaskInterruptionMode.NONE,
                actionStep(ADD_MANAGED)
        );

        TaskRunSnapshot run = manager.start(task, List.of());
        manager.tick();

        assertEquals(TaskRunState.SUCCEEDED, runState(manager, run.runId()));
        assertTrue(manager.status().isEmpty());
    }

    private static TaskRunManager managerWithRegistry(
            RecordingNavigation navigation
    ) {
        return new TaskRunManager(navigation, registry(navigation));
    }

    private static TaskModuleRuntimeRegistry registry(
            RecordingNavigation navigation,
            TaskModuleRuntimeFactory... extraFactories
    ) {
        return registryWithDrops(navigation, new EmptyDroppedItems(), extraFactories);
    }

    private static TaskModuleRuntimeRegistry registryWithDrops(
            RecordingNavigation navigation, DroppedItemObserverPort drops,
            TaskModuleRuntimeFactory... extraFactories
    ) {
        List<TaskModuleRuntimeFactory> factories = new ArrayList<>(
                StandardTaskModuleFactories.create()
        );
        factories.add(factory(ADD_MANAGED));
        factories.add(factory(CLEAR_MANAGED));
        factories.add(factory(SLOW));
        factories.add(factory(NOOP));
        factories.addAll(List.of(extraFactories));
        return new TaskModuleRuntimeRegistry(
                navigation,
                drops,
                (region, types, volume) ->
                        ContainerScanResult.failure("unused"),
                new FailedTransfers(),
                new FailedWithdrawals(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                FoodConsumptionPort.UNAVAILABLE,
                (zone, types, volume) -> CategoryZoneResult.failure("unused"),
                factories
        );
    }

    private static TaskModuleRuntimeFactory factory(String moduleId) {
        return new TaskModuleRuntimeFactory() {
            @Override
            public String moduleId() {
                return moduleId;
            }

            @Override
            public TaskModuleExecutor create(
                    TaskStep step,
                    TaskExecutionContext context
            ) {
                return switch (moduleId) {
                    case ADD_MANAGED -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            context.workspace().recordManagedAcquired(
                                    "minecraft:iron_ingot",
                                    1
                            );
                            return TaskModuleResult.succeeded("added");
                        }
                    };
                    case CLEAR_MANAGED -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            context.workspace().consumeManaged(
                                    "minecraft:iron_ingot",
                                    1
                            );
                            return TaskModuleResult.succeeded("cleared");
                        }
                    };
                    case SLOW -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            return TaskModuleResult.waiting("slow");
                        }
                    };
                    default -> new TestAction(context) {
                        @Override
                        public TaskModuleResult start() {
                            return TaskModuleResult.succeeded("noop");
                        }
                    };
                };
            }
        };
    }

    private abstract static class TestAction implements TaskModuleExecutor {
        private final TaskExecutionContext context;

        protected TestAction(TaskExecutionContext context) {
            this.context = context;
        }

        @Override
        public TaskModuleResult tick() {
            return TaskModuleResult.waiting("slow");
        }

        @Override
        public void cancel() {
        }
    }

    private static TaskDefinition withCleanup(
            TaskDefinition task,
            TaskDefinition cleanup,
            boolean requeue
    ) {
        return new TaskDefinition(
                task.id(),
                task.name(),
                task.priority(),
                task.repeatWhenIdle(),
                task.steps(),
                TaskInterruptionMode.CUSTOM,
                "task:" + cleanup.id(),
                false,
                requeue
        );
    }

    private static TaskDefinition task(
            String name,
            TaskPriority priority,
            TaskInterruptionMode mode,
            TaskStep... steps
    ) {
        return new TaskDefinition(
                UUID.randomUUID(),
                name,
                priority,
                false,
                List.of(steps),
                mode,
                null,
                false,
                false
        );
    }

    private static TaskStep actionStep(String moduleId) {
        return TaskStep.action(UUID.randomUUID(), moduleId, Map.of());
    }

    private static TaskStep moveStep(UUID coordinateId) {
        return TaskStep.action(
                UUID.randomUUID(),
                "MOVE_TO_COORDINATE",
                Map.of("targetRef", "coordinate:" + coordinateId)
        );
    }

    private static CoordinatePoint coordinate(UUID id) {
        return coordinate(id, 10);
    }

    private static CoordinatePoint coordinate(UUID id, int x) {
        return new CoordinatePoint(id, "Target", x, 64, 10);
    }

    private static AppConfig config(
            List<CoordinatePoint> coordinates,
            List<TaskDefinition> tasks
    ) {
        return new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                tasks,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static TaskRunState runState(TaskRunManager manager, UUID runId) {
        return manager.runs().stream()
                .filter(run -> run.runId().equals(runId))
                .findFirst()
                .orElseThrow()
                .state();
    }

    private static final class RecordingNavigation implements NavigationPort {
        private final Deque<NavigationStatus> statuses = new ArrayDeque<>();
        private final Deque<StopOutcome> stopOutcomes = new ArrayDeque<>();
        private final List<BlockPosition> positions = new ArrayList<>();
        private BlockPosition failAt;
        private boolean active;
        private int requestStopCount;
        private int pollStopCount;

        @Override
        public NavigationStatus start(BlockPosition target) {
            positions.add(target);
            active = true;
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return status(target, null);
        }

        @Override
        public NavigationStatus status(BlockPosition target, Integer radius) {
            if (!active) {
                return NavigationStatus.FAILED;
            }
            if (target.equals(failAt)) {
                active = false;
                return NavigationStatus.FAILED;
            }
            NavigationStatus status = statuses.isEmpty()
                    ? NavigationStatus.ARRIVED
                    : statuses.removeFirst();
            if (status != NavigationStatus.NAVIGATING) {
                active = false;
            }
            return status;
        }

        @Override
        public void cancel() {
            active = false;
        }

        @Override
        public StopOutcome requestStop() {
            active = false;
            requestStopCount++;
            return stopOutcomes.isEmpty()
                    ? StopOutcome.CLEAN
                    : stopOutcomes.removeFirst();
        }

        @Override
        public StopOutcome pollStop() {
            pollStopCount++;
            return stopOutcomes.isEmpty()
                    ? StopOutcome.CLEAN
                    : stopOutcomes.removeFirst();
        }

        private void failAt(BlockPosition position) {
            failAt = position;
        }

        private void stopOutcomes(StopOutcome... outcomes) {
            stopOutcomes.addAll(List.of(outcomes));
        }

        private List<BlockPosition> positions() {
            return List.copyOf(positions);
        }

        private int requestStopCount() {
            return requestStopCount;
        }

        private int pollStopCount() {
            return pollStopCount;
        }
    }

    private static class EmptyDroppedItems
            implements DroppedItemObserverPort {
        @Override
        public List<DroppedItem> observe(BlockPosition center, int radius) {
            return List.of();
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return List.of();
        }
    }

    private static final class FailedTransfers implements ContainerTransferPort {
        @Override
        public ContainerTransferResult startDeposit(
                ContainerTarget target,
                Map<String, Integer> items
        ) {
            return ContainerTransferResult.failed("unused", items);
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            return ContainerTransferResult.failed("unused", Map.of());
        }

        @Override
        public void cancelDeposit() {
        }
    }

    private static final class FailedWithdrawals
            implements ContainerWithdrawalPort {
        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            return ContainerWithdrawalResult.failed("unused", Map.of(), false);
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.failed("unused", Map.of(), false);
        }

        @Override
        public void cancelWithdrawal() {
        }
    }
}
