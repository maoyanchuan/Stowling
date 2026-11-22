package dev.stowling.v2.runtime;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.PickupReceipt;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CoordinateCatalog;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskInteractionCompiler;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class TaskRunManager {
    private final TaskDiagnosticLog diagnostics = new TaskDiagnosticLog();

    public TaskDiagnosticLog diagnostics() {
        return diagnostics;
    }
    private static final int HISTORY_LIMIT = 50;
    private static final int BACKGROUND_REPEAT_DELAY_TICKS = 100;

    private final NavigationPort navigation;
    private final TaskModuleRuntimeRegistry registry;
    private final TaskTriggerManager triggers;
    private final Deque<TaskRunSnapshot> history = new ArrayDeque<>();
    private final List<QueuedRun> queue = new ArrayList<>();
    private Map<UUID, TaskDefinition> knownTasks = Map.of();
    private List<TaskTriggerDefinition> knownTriggers = List.of();
    private ActiveRun active;
    private long nextSequence;
    private long ticks;
    private AppConfig inventoryConfig;
    private long nextInventoryRecoveryTick;
    private final Set<UUID> pendingInventoryClassification = new LinkedHashSet<>();

    public TaskRunManager(NavigationPort navigation) {
        this(navigation, TaskModuleRuntimeRegistry.unavailablePorts(navigation));
    }

    public TaskRunManager(
            NavigationPort navigation,
            TaskModuleRuntimeRegistry registry
    ) {
        this(navigation, registry, dev.stowling.automation.WorldObservation::disconnected);
    }

    public TaskRunManager(NavigationPort navigation, TaskModuleRuntimeRegistry registry,
            java.util.function.Supplier<dev.stowling.automation.WorldObservation> world) {
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.triggers = new TaskTriggerManager(
                registry.droppedItems(),
                registry.foodConsumption(),
                navigation,
                new TaskTriggerManager.Host() {
                    @Override
                    public TaskRunSnapshot start(
                            TaskDefinition task,
                            TaskPriority priority,
                            AppConfig config
                    ) {
                        return startFromConfig(
                                task,
                                config,
                                priority,
                                false
                        );
                    }

                    @Override
                    public boolean taskPending(UUID taskId) {
                        return isTaskPending(taskId);
                    }

                    @Override
                    public boolean canRunScheduledTasks() {
                        var observation = world.get();
                        return observation.connected() && observation.alive();
                    }

                    @Override
                    public boolean hasProtectedFood(String itemId) {
                        return !registry.inventory().availableProtectedFoodKeys(itemId).isEmpty();
                    }

                    @Override
                    public boolean hasCollectedInput(UUID taskId) {
                        return registry.inventory().hasCollectedInput(taskId);
                    }

                    @Override
                    public boolean isRecoveryDrop(dev.stowling.automation.DroppedItem item) {
                        return registry.storageAutomation().isRecoveryDrop(item);
                    }
                }
        );
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates
    ) {
        return start(task, coordinates, List.of());
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions
    ) {
        return start(task, coordinates, regions, List.of());
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones
    ) {
        return start(task, coordinates, regions, categoryZones, null);
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            TaskPriority priorityOverride
    ) {
        return enqueueStart(
                task,
                coordinates,
                regions,
                categoryZones,
                Map.of(),
                knownTasks,
                knownTriggers,
                priorityOverride,
                true,
                true
        );
    }

    public synchronized TaskRunSnapshot start(
            TaskDefinition task,
            AppConfig config,
            TaskPriority priorityOverride
    ) {
        return startFromConfig(task, config, priorityOverride, true);
    }

    public synchronized TaskRunSnapshot startOnce(TaskDefinition task, AppConfig config) {
        return startFromConfig(task, config, null, false);
    }

    private TaskRunSnapshot startFromConfig(
            TaskDefinition task,
            AppConfig config,
            TaskPriority priorityOverride,
            boolean allowRepeat
    ) {
        Objects.requireNonNull(config, "config");
        return enqueueStart(
                task,
                config.coordinates(),
                config.regions(),
                taskCategoryZones(task, config),
                config.coordinateCatalog().warehouses().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                CoordinateCatalog.Warehouse::id, warehouse -> warehouse)),
                indexTasks(config.tasks()),
                config.triggers(),
                priorityOverride,
                allowRepeat,
                true
        );
    }

    private static List<CategoryZoneDefinition> taskCategoryZones(TaskDefinition task,
            AppConfig config) {
        var owner = config.autoClassifyTasks().stream().filter(definition ->
                definition.classificationTaskId().equals(task.id())).findFirst().orElse(null);
        if (owner == null) return config.categoryZones();
        return config.categoryZones().stream().map(zone -> {
            var policy = owner.classificationAreas().stream().filter(area ->
                    area.categoryZoneRef().equals("category-zone:" + zone.id()))
                    .map(dev.stowling.v2.model.AutoClassifyAreaDefinition::storagePolicy)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (policy == null) return zone;
            return new CategoryZoneDefinition(zone.id(), zone.name(), zone.bindings().stream()
                    .map(binding -> new dev.stowling.v2.model.CategoryBindingDefinition(
                            binding.id(), binding.markerArea(), binding.storageArea(),
                            binding.ruleId(), binding.ignoredMarkerBlockIds(),
                            binding.containerOverrides(), binding.standingArea(),
                            new dev.stowling.v2.model.StoragePolicy(policy.mode(),
                                    policy.loosePercent(), policy.allowPartialBoxes(),
                                    binding.storagePolicy().packingArea(), policy.refillLoose(),
                                    policy.looseSlots(), policy.partialBoxDestinationRef()),
                            binding.wholeStorage()))
                    .toList());
        }).toList();
    }

    private TaskRunSnapshot enqueueStart(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            Map<UUID, CoordinateCatalog.Warehouse> warehouses,
            Map<UUID, TaskDefinition> tasks,
            List<TaskTriggerDefinition> triggerDefinitions,
            TaskPriority priorityOverride,
            boolean allowRepeat,
            boolean deduplicate
    ) {
        PreparedRun prepared = prepare(
                task,
                coordinates,
                regions,
                categoryZones,
                warehouses,
                tasks,
                triggerDefinitions,
                priorityOverride,
                allowRepeat
        );
        if (deduplicate) {
            if (active != null && active.prepared.task.id().equals(task.id())) {
                return active.snapshot;
            }
            Optional<QueuedRun> existing = queue.stream()
                    .filter(run -> run.prepared.task.id().equals(task.id()))
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get().snapshot;
            }
        }
        QueuedRun queued = newQueuedRun(prepared, ticks, "排队等待");
        queue.add(queued);
        retain(queued.snapshot);
        if (active == null) {
            TaskRunSnapshot started = startNextQueued();
            return started != null
                    && started.runId().equals(queued.snapshot.runId())
                    ? started
                    : queued.snapshot;
        }
        requestPreemptionIfNeeded(queued);
        return queued.snapshot;
    }

    public synchronized void tick() {
        ticks++;
        boolean inventoryAvailable = registry.inventory().observe().isPresent();
        if (inventoryAvailable && active == null) registry.inventory().reconcileUnassignedItems();
        triggers.tick();
        if (inventoryAvailable) {
            queueInventoryClassification();
            recoverUnassignedInventory();
        }
        if (active == null) {
            startNextQueued();
        }
        if (active == null) {
            return;
        }
        if (active.forceStopRequested) {
            tickForceStopping();
        } else if (!active.interruptionFailed) {
            if (active.stopRequested) {
                tickInterrupting();
            } else {
                tickFlow();
            }
        }
        if (active == null) {
            startNextQueued();
        }
    }

    public synchronized void cancel(UUID runId) {
        if (active != null && active.snapshot.runId().equals(runId)) {
            if (active.forceStopRequested) {
                return;
            }
            if (active.interruptionFailed) {
                throw new IllegalStateException(
                        "中断收尾失败，请重试或显式跳过"
                );
            }
            active.userCancelRequested = true;
            if (active.stopRequested) {
                active.terminalState = TaskRunState.CANCELLED;
                active.terminalMessage = "任务已取消";
                update(
                        active.interruptionRunning
                                ? TaskRunState.INTERRUPTING
                                : TaskRunState.INTERRUPTING,
                        active.interruptionRunning
                                ? "正在完成中断收尾后取消"
                                : "正在取消任务"
                );
                return;
            }
            startTermination(TaskRunState.CANCELLED, "任务已取消");
            return;
        }
        for (QueuedRun queued : List.copyOf(queue)) {
            if (!queued.snapshot.runId().equals(runId)) {
                continue;
            }
            queue.remove(queued);
            replaceRetained(snapshot(
                    queued,
                    TaskRunState.CANCELLED,
                    0,
                    "排队任务已取消",
                    Instant.now()
            ));
            return;
        }
        throw new TaskRunNotFoundException();
    }

    public synchronized void cancelTask(UUID taskId) {
        if (active != null && active.prepared.task.id().equals(taskId)
                && !active.interruptionFailed) {
            cancel(active.snapshot.runId());
        }
        for (QueuedRun queued : List.copyOf(queue)) {
            if (queued.prepared.task.id().equals(taskId)) {
                cancel(queued.snapshot.runId());
            }
        }
    }

    public synchronized void forceStop(UUID runId) {
        if (active == null || !active.snapshot.runId().equals(runId)) {
            throw new TaskRunNotFoundException();
        }
        if (active.forceStopRequested) {
            return;
        }
        active.forceStopRequested = true;
        active.userCancelRequested = true;
        active.stopRequested = true;
        active.navigationStopRequested = false;
        active.interruptionRunning = false;
        active.interruptionFailed = false;
        active.terminalState = TaskRunState.CANCELLED;
        active.terminalMessage = "任务已强制停止";
        active.interruptionFailureMessage = "强制停止未完成";
        update(TaskRunState.INTERRUPTING, "正在强制停止任务");
    }

    public synchronized void retryInterruption(UUID runId) {
        if (active == null || !active.snapshot.runId().equals(runId)) {
            throw new TaskRunNotFoundException();
        }
        if (!active.interruptionFailed) {
            throw new IllegalStateException("当前运行没有失败的收尾流程");
        }
        if (active.prepared.interruptionTask == null && !requiresGlobalCleanup()
                && !active.stopFailurePending) {
            throw new IllegalStateException("当前运行没有可重试的收尾流程");
        }
        active.interruptionFailed = false;
        active.stopRequested = true;
        active.interruptionRunning = false;
        active.interruptionRecovery = true;
        active.retryAfterRecovery = true;
        active.navigationStopConfirmed = false;
        active.navigationStopRequested = false;
        if (active.interruptionFlow != null) {
            active.interruptionFlow.requestStop();
        }
        update(
                TaskRunState.INTERRUPTING,
                "正在安全停止旧收尾动作并重新确认导航"
        );
    }

    public synchronized void skipInterruption(UUID runId) {
        if (active == null || !active.snapshot.runId().equals(runId)) {
            throw new TaskRunNotFoundException();
        }
        if (!active.interruptionFailed) {
            throw new IllegalStateException("当前运行没有失败的收尾流程");
        }
        if (active.flow != null && !active.flow.safeToStop()) {
            throw new IllegalStateException("仍有在途动作，不能跳过中断收尾");
        }
        if (active.interruptionFlow != null
                && !active.interruptionFlow.safeToStop()) {
            throw new IllegalStateException("收尾动作仍在途，不能跳过");
        }
        if (!active.navigationStopConfirmed) {
            throw new IllegalStateException("导航停止尚未确认，不能跳过中断收尾");
        }
        if (active.workspace.hasManagedItems()) {
            throw new IllegalStateException(
                    "仍有受管物品，不能跳过中断收尾"
            );
        }
        finishRun(
                TaskRunState.FAILED,
                "用户显式跳过失败的中断收尾，无受管物品残留"
        );
    }

    public synchronized Optional<TaskRunSnapshot> status() {
        if (active != null) {
            return Optional.of(active.snapshot);
        }
        return queue.stream()
                .sorted(queueOrder())
                .findFirst()
                .map(run -> snapshot(
                        run,
                        TaskRunState.QUEUED,
                        0,
                        run.readyAtTick <= ticks
                                ? "排队等待"
                                : "等待后台任务冷却",
                        run.snapshot.updatedAt()
                ));
    }

    public synchronized List<TaskRunSnapshot> runs() {
        return List.copyOf(history);
    }

    public synchronized List<TaskRunSnapshot> queued() {
        return queue.stream()
                .sorted(queueOrder())
                .map(run -> snapshot(
                        run,
                        TaskRunState.QUEUED,
                        0,
                        run.readyAtTick <= ticks
                                ? "排队等待"
                                : "等待后台任务冷却",
                        run.snapshot.updatedAt()
                ))
                .toList();
    }

    public synchronized Optional<TaskRunSnapshot> pendingTask(UUID taskId) {
        if (active != null && active.prepared.task.id().equals(taskId)) {
            return Optional.of(active.snapshot);
        }
        return queue.stream().filter(run -> run.prepared.task.id().equals(taskId))
                .map(run -> run.snapshot).findFirst();
    }

    public synchronized boolean hasPendingTask(UUID taskId) {
        return isTaskPending(taskId);
    }

    public synchronized void refreshTriggers(AppConfig config) {
        inventoryConfig = config;
        knownTriggers = List.copyOf(config.triggers());
        knownTasks = indexKnownTasks(config.tasks(), knownTriggers);
        triggers.refresh(config);
        registry.inventory().retainCollectionTasks(triggers.collectionTasks());
    }

    public synchronized void receivedPickup(PickupReceipt receipt) {
        UUID task = triggers.collectionOwner(receipt);
        if (task != null) {
            registry.inventory().recordPickup(task, receipt.itemId(), receipt.count());
        }
    }

    private void recoverUnassignedInventory() {
        if (active != null || !queue.isEmpty() || inventoryConfig == null
                || ticks < nextInventoryRecoveryTick
                || !registry.inventory().needsRecovery()) {
            return;
        }
        boolean classify = registry.inventory().hasClassificationRequests()
                && classificationAvailable();
        if (!classify && !registry.inventory().hasAbandonedAccounts()) return;
        nextInventoryRecoveryTick = ticks + BACKGROUND_REPEAT_DELAY_TICKS;
        for (AutoClassifyDefinition definition
                : inventoryConfig.autoClassifyTasks()) {
            if (definition.enabled()
                    && (definition.classificationEnabled()
                            || definition.collectionEnabled() && !classify)) {
                TaskDefinition task = knownTasks.get(definition.id());
                if (task != null) {
                    List<TaskStep> steps = new ArrayList<>(
                            dev.stowling.v2.model.TaskFlowTemplates.reconcileInventory(
                                    definition.cacheLocationRef(), Map.of()));
                    if (definition.classificationEnabled()) {
                        steps.add(TaskStep.createTask(UUID.randomUUID(), Map.of(
                                "taskRef", "task:" + definition.classificationTaskId(),
                                "priority", TaskPriority.BACKGROUND.name())));
                    }
                    TaskDefinition recovery = new TaskDefinition(task.id(), task.name(),
                            TaskPriority.BACKGROUND, false, steps, task.interruptionMode(),
                            task.interruptionTaskRef(), false, false, task.folderId(),
                            task.cleanupPolicy());
                    startFromConfig(recovery, inventoryConfig, TaskPriority.BACKGROUND, false);
                    return;
                }
            }
        }
    }

    private void queueInventoryClassification() {
        if (inventoryConfig == null) {
            return;
        }
        for (UUID taskId : Set.copyOf(pendingInventoryClassification)) {
            boolean enabled = inventoryConfig.autoClassifyTasks().stream().anyMatch(definition ->
                    definition.enabled() && definition.classificationEnabled()
                            && definition.classificationTaskId().equals(taskId)
            );
            if (enabled && !isTaskPending(taskId)) {
                TaskDefinition task = knownTasks.get(taskId);
                if (task != null) {
                    startFromConfig(task, inventoryConfig, TaskPriority.BACKGROUND, false);
                }
            }
            pendingInventoryClassification.remove(taskId);
        }
    }

    public synchronized List<TaskTriggerSnapshot> triggerStatuses() {
        return triggers.snapshots();
    }

    public synchronized boolean busy() {
        return active != null;
    }

    public synchronized dev.stowling.v2.control.PlayerInventoryResponse playerInventory() {
        var observer = registry.droppedItems();
        return registry.inventory().view(active != null || !queue.isEmpty(),
                classificationAvailable(), observer.playerItemNames(),
                observer.playerInventorySlots());
    }

    public String playerItemIcon(String model) {
        return registry.droppedItems().playerItemIcon(model);
    }

    private boolean classificationAvailable() {
        return inventoryConfig != null
                && inventoryConfig.autoClassifyTasks().stream().anyMatch(definition ->
                        definition.enabled() && definition.classificationEnabled());
    }

    public synchronized dev.stowling.v2.control.PlayerInventoryResponse allocatePlayerInventory(
            String revision, String key, int keepCount) {
        if (active != null || !queue.isEmpty()) {
            throw new IllegalStateException("请等待任务和收尾结束后调整背包归属");
        }
        registry.inventory().allocate(revision, key, keepCount);
        nextInventoryRecoveryTick = ticks;
        return playerInventory();
    }

    public synchronized void cancelAll() {
        for (QueuedRun queued : List.copyOf(queue)) {
            TaskRunSnapshot cancelled = snapshot(
                    queued,
                    TaskRunState.CANCELLED,
                    0,
                    "排队任务已取消",
                    Instant.now()
            );
            replaceRetained(cancelled);
        }
        queue.clear();
        if (active == null || active.interruptionFailed) {
            return;
        }
        cancel(active.snapshot.runId());
    }

    private void tickFlow() {
        try {
            TaskFlowExecutor currentFlow = flow();
            TaskModuleResult result = currentFlow.tick();
            syncCompletedSteps();
            if (active.stopRequested) {
                return;
            }
            switch (result.state()) {
                case RUNNING, WAITING -> update(
                        result.state() == TaskModuleState.WAITING
                                ? TaskRunState.WAITING
                                : TaskRunState.RUNNING,
                        result.message()
                );
                case FAILED -> {
                    if (active.prepared.interruptionTask == null && !requiresGlobalCleanup()) {
                        finishRun(
                                TaskRunState.FAILED,
                                withManagedWarning(result.message())
                        );
                    } else {
                        startTermination(
                                TaskRunState.FAILED,
                                result.message()
                        );
                    }
                }
                case SUCCEEDED -> {
                    if (requiresGlobalCleanup() && (active.workspace.hasManagedItems()
                            || registry.storageAutomation().hasTemporaryResources(
                                    active.workspace.inventoryAccount().ownerId()))) {
                        startTermination(TaskRunState.SUCCEEDED, "任务完成，正在全局收尾");
                    } else {
                        finishRun(
                                TaskRunState.SUCCEEDED,
                                active.workspace.hasManagedItems()
                                        ? "任务已完成，但仍有受管物品"
                                        : "任务已完成"
                        );
                    }
                }
            }
        } catch (RuntimeException exception) {
            syncCompletedSteps();
            String message = "流程执行失败: " + safeMessage(exception);
            if (active.prepared.interruptionTask == null && !requiresGlobalCleanup()) {
                finishRun(
                        TaskRunState.FAILED,
                        withManagedWarning(message)
                );
            } else {
                startTermination(TaskRunState.FAILED, message);
            }
        }
    }

    private void startTermination(TaskRunState state, String message) {
        if (active.stopRequested) {
            return;
        }
        active.userCancelRequested = active.userCancelRequested
                || state == TaskRunState.CANCELLED;
        active.terminalState = state;
        active.terminalMessage = message;
        active.stopRequested = true;
        active.navigationStopConfirmed = false;
        if (active.flow != null) {
            active.completedSteps = active.flow.completedTopLevelSteps();
            active.flow.requestStop();
        }
        update(
                TaskRunState.INTERRUPTING,
                state == TaskRunState.PREEMPTED
                        ? message
                        : "正在停止当前动作并准备收尾"
        );
    }

    private void tickForceStopping() {
        // Module cancellation and navigation must run on the Minecraft client thread.
        cancelForForceStop(active.flow);
        cancelForForceStop(active.interruptionFlow);
        active.flow = null;
        active.interruptionFlow = null;

        StopOutcome outcome = requestOrPollNavigationStop();
        if (outcome == StopOutcome.PENDING) {
            active.navigationStopRequested = true;
            update(TaskRunState.INTERRUPTING, "正在强制停止任务，等待导航停止");
            return;
        }
        active.navigationStopRequested = false;
        active.navigationStopConfirmed = outcome == StopOutcome.CLEAN;
        if (!active.navigationStopConfirmed) {
            active.forceStopRequested = false;
            markInterruptionFailed("强制停止未完成：导航停止状态未知", true);
            return;
        }

        var account = active.workspace.inventoryAccount();
        registry.storageAutomation().releaseTemporaryResources(account.ownerId());
        if (active.workspace.hasManagedItems() && registry.inventory().observe().isPresent()) {
            registry.inventory().reconcileMissingItems(account);
        }
        finishTerminated();
    }

    private void cancelForForceStop(TaskFlowExecutor executor) {
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException exception) {
                active.stopWarning = "取消当前动作失败: " + safeMessage(exception);
            }
        }
    }

    private StopOutcome requestOrPollNavigationStop() {
        try {
            return active.navigationStopRequested
                    ? navigation.pollStop()
                    : navigation.requestStop();
        } catch (RuntimeException exception) {
            active.stopWarning = "停止导航失败: " + safeMessage(exception);
            return StopOutcome.UNSAFE;
        }
    }

    private void tickInterrupting() {
        if (active.interruptionRecovery) {
            tickInterruptionRecovery();
            return;
        }
        if (active.interruptionRunning) {
            tickInterruptionFlow();
            return;
        }
        if (active.flow != null && !active.flow.safeToStop()) {
            try {
                TaskModuleResult result = active.flow.tickWhileStopping();
                if (result.state() == TaskModuleState.RUNNING
                        || result.state() == TaskModuleState.WAITING) {
                    update(TaskRunState.INTERRUPTING, result.message());
                    return;
                }
                if (result.state() == TaskModuleState.FAILED) {
                    active.stopWarning = result.message();
                }
            } catch (RuntimeException exception) {
                active.stopWarning = "停止当前动作失败: "
                        + safeMessage(exception);
            }
        }
        if (active.flow != null && !active.flow.safeToStop()) {
            update(TaskRunState.INTERRUPTING, "等待在途操作确认");
            return;
        }
        StopOutcome outcome = requestOrPollNavigationStop();
        if (outcome == StopOutcome.PENDING) {
            active.navigationStopRequested = true;
            update(TaskRunState.INTERRUPTING, "正在停止导航");
            return;
        }
        if (outcome == StopOutcome.UNSAFE) {
            markInterruptionFailed(
                    "导航停止状态未知，已保留执行槽位",
                    true
            );
            return;
        }
        active.navigationStopConfirmed = true;
        if (active.flow != null) {
            active.flow.cancel();
            active.flow = null;
        }
        beginInterruption();
    }

    private boolean requiresGlobalCleanup() {
        return registry.storageAutomation().cleanupEnabled()
                || active != null && active.prepared.task.cleanupPolicy().destinationRef() != null
                || active != null && active.prepared.task.cleanupPolicy().resumeMode()
                        == dev.stowling.v2.model.TaskResumeMode.CONTINUE
                || active != null && registry.storageAutomation().hasTemporaryResources(
                        active.workspace.inventoryAccount().ownerId());
    }

    private void beginInterruption() {
        if (requiresGlobalCleanup()) {
            registry.inventory().observe();
            if (!registry.storageAutomation().hasTemporaryResources(
                    active.workspace.inventoryAccount().ownerId())) {
                registry.inventory().claimForCleanup(active.workspace.inventoryAccount());
            }
        }
        TaskDefinition cleanup = active.prepared.interruptionTask;
        if (requiresGlobalCleanup()) {
            cleanup = new TaskDefinition(UUID.randomUUID(), "全局物品收尾",
                    List.of(TaskStep.action(UUID.randomUUID(), "GLOBAL_INVENTORY_CLEANUP",
                            Map.of("overflowEnabled", Boolean.toString(
                                            usesModule(active.prepared.steps,
                                                    ConfigValidator.PACK_OVERFLOW))))));
        }
        if (cleanup == null || !active.workspace.hasManagedItems()
                && !registry.storageAutomation().hasTemporaryResources(
                        active.workspace.inventoryAccount().ownerId())) {
            finishTerminated();
            return;
        }
        active.navigationStopRequested = false;
        active.navigationStopConfirmed = false;
        try {
            active.interruptionFlow = newFlow(active, cleanup, false);
            active.interruptionRunning = true;
            update(TaskRunState.INTERRUPTING, "正在执行中断收尾");
        } catch (RuntimeException exception) {
            failInterruption("无法启动中断收尾: " + safeMessage(exception));
        }
    }

    private void tickInterruptionFlow() {
        try {
            TaskModuleResult result = active.interruptionFlow.tick();
            switch (result.state()) {
                case RUNNING, WAITING -> update(
                        TaskRunState.INTERRUPTING,
                        result.message()
                );
                case FAILED -> failInterruption(
                        "中断收尾失败: " + result.message()
                );
                case SUCCEEDED -> {
                    if (active.workspace.hasManagedItems()) {
                        failInterruption(
                                "中断收尾结束但仍有 "
                                        + active.workspace.managedItemCount()
                                        + " 个受管物品"
                        );
                    } else {
                        finishTerminated();
                    }
                }
            }
        } catch (RuntimeException exception) {
            failInterruption(
                    "中断收尾异常: " + safeMessage(exception)
            );
        }
    }

    private static boolean usesModule(List<TaskStep> steps, String moduleId) {
        return steps.stream().anyMatch(step -> moduleId.equals(step.moduleId())
                || usesModule(step.steps(), moduleId) || usesModule(step.otherwise(), moduleId));
    }

    private void failInterruption(String message) {
        active.interruptionRunning = false;
        active.interruptionRecovery = true;
        active.retryAfterRecovery = false;
        active.stopFailurePending = false;
        active.interruptionFailureMessage = message;
        active.stopRequested = true;
        active.navigationStopRequested = false;
        active.navigationStopConfirmed = false;
        if (active.interruptionFlow != null) {
            active.interruptionFlow.requestStop();
        }
        update(TaskRunState.INTERRUPTING, "正在安全停止中断收尾动作");
    }

    private void markInterruptionFailed(
            String message,
            boolean stopFailure
    ) {
        active.interruptionRunning = false;
        active.interruptionRecovery = false;
        active.interruptionFailed = true;
        active.stopFailurePending = stopFailure;
        String warning = active.stopWarning == null || active.stopWarning.isBlank()
                ? ""
                : "；" + active.stopWarning;
        String cause = (active.terminalState == TaskRunState.FAILED
                || active.terminalState == TaskRunState.PREEMPTED)
                && active.terminalMessage != null && !active.terminalMessage.isBlank()
                ? "；原任务停止原因：" + active.terminalMessage : "";
        update(TaskRunState.INTERRUPTION_FAILED, message + cause + warning);
    }

    private void tickInterruptionRecovery() {
        if (active.interruptionFlow != null
                && !active.interruptionFlow.safeToStop()) {
            try {
                TaskModuleResult result = active.interruptionFlow
                        .tickWhileStopping();
                if (result.state() == TaskModuleState.RUNNING
                        || result.state() == TaskModuleState.WAITING) {
                    update(TaskRunState.INTERRUPTING, result.message());
                    return;
                }
                if (result.state() == TaskModuleState.FAILED) {
                    active.interruptionFailureMessage = result.message();
                }
            } catch (RuntimeException exception) {
                active.interruptionFailureMessage =
                        "停止中断收尾动作失败: " + safeMessage(exception);
            }
        }
        if (active.interruptionFlow != null
                && !active.interruptionFlow.safeToStop()) {
            update(TaskRunState.INTERRUPTING, "等待收尾动作确认");
            return;
        }
        StopOutcome outcome = requestOrPollNavigationStop();
        if (outcome == StopOutcome.PENDING) {
            active.navigationStopRequested = true;
            update(TaskRunState.INTERRUPTING, "正在重新确认导航停止");
            return;
        }
        if (outcome == StopOutcome.UNSAFE) {
            markInterruptionFailed(
                    active.interruptionFailureMessage
                            + "；导航停止状态未知",
                    true
            );
            return;
        }
        active.navigationStopConfirmed = true;
        active.interruptionRecovery = false;
        active.navigationStopRequested = false;
        active.stopFailurePending = false;
        if (active.interruptionFlow != null) {
            active.interruptionFlow.cancel();
        }
        if (active.retryAfterRecovery) {
            active.retryAfterRecovery = false;
            active.interruptionFailureMessage = "";
            active.interruptionRunning = false;
            beginInterruption();
            return;
        }
        markInterruptionFailed(
                active.interruptionFailureMessage,
                false
        );
    }

    private void finishTerminated() {
        String message = withManagedWarning(active.terminalMessage);
        if (active.stopWarning != null && !active.stopWarning.isBlank()) {
            message += "；" + active.stopWarning;
        }
        finishRun(active.terminalState, message);
    }

    private String withManagedWarning(String message) {
        if (active == null || !active.workspace.hasManagedItems()) {
            return message;
        }
        return message + "；未执行清理，仍有 "
                + active.workspace.managedItemCount()
                + " 个受管物品";
    }

    private void finishRun(TaskRunState state, String message) {
        diagnostics.append(active.snapshot.runId(), "TASK", "", "RUN", "STATE", state.name(),
                message + "; managed=" + active.workspace.managedItems(), 0, false);
        publishWorkspaceEvents();
        PreparedRun completed = active.prepared;
        Continuation continuation = completed.task.cleanupPolicy().resumeMode()
                == dev.stowling.v2.model.TaskResumeMode.CONTINUE
                ? new Continuation(active.workspace, active.completedSteps) : null;
        boolean hadManagedItems = active.workspace.hasManagedItems();
        TaskRunSnapshot old = active.snapshot;
        int completedSteps = state == TaskRunState.SUCCEEDED
                ? old.totalSteps()
                : active.completedSteps;
        TaskRunSnapshot finished = new TaskRunSnapshot(
                old.runId(),
                old.taskId(),
                old.taskName(),
                old.priority(),
                old.repeating(),
                state,
                completedSteps,
                old.totalSteps(),
                message,
                old.startedAt(),
                Instant.now()
        );
        replaceRetained(finished);
        registry.inventory().closeAccount(active.workspace.inventoryAccount());
        active = null;
        if (state == TaskRunState.SUCCEEDED
                && completed.repeating
                && !hadManagedItems) {
            enqueuePrepared(
                    completed,
                    BACKGROUND_REPEAT_DELAY_TICKS,
                    "等待后台任务冷却",
                    true
            );
        }
        if (state == TaskRunState.PREEMPTED
                && !hadManagedItems
                && completed.task.requeueAfterInterruption()) {
            enqueuePrepared(
                    completed,
                    0,
                    continuation == null ? "被中断，等待重新运行"
                            : "已保留交付进度，等待恢复当前阶段",
                    true,
                    continuation
            );
        }
    }

    private TaskFlowExecutor flow() {
        if (active.flow == null) {
            active.flow = newFlow(active, active.prepared.task, true);
        }
        return active.flow;
    }

    private void syncCompletedSteps() {
        if (active != null && active.flow != null) {
            active.completedSteps = active.flow.completedTopLevelSteps();
        }
    }

    private TaskFlowExecutor newFlow(
            ActiveRun run,
            TaskDefinition task,
            boolean allowTaskCreation
    ) {
        Objects.requireNonNull(task, "task");
        return new TaskFlowExecutor(
                task.steps(),
                run.context,
                new TaskFlowExecutor.Host() {
                    @Override
                    public TaskModuleExecutor createAction(TaskStep step) {
                        return registry.create(step, run.context);
                    }

                    @Override
                    public void createTask(
                            String taskRef,
                            TaskPriority priority
                    ) {
                        createIndependentTask(run, taskRef, priority);
                    }
                },
                allowTaskCreation,
                allowTaskCreation ? run.startStep : 0
        );
    }

    private void createIndependentTask(
            ActiveRun run,
            String taskRef,
            TaskPriority priority
    ) {
        ResourceReference reference = ResourceReference.parse(taskRef);
        if (reference.type() != ResourceType.TASK) {
            throw new IllegalArgumentException("taskRef must reference a task");
        }
        TaskDefinition task = run.prepared.tasks.get(reference.uuid());
        if (task == null) {
            throw new IllegalArgumentException(
                    "task does not exist: " + reference
            );
        }
        if (isTaskPending(task.id())) {
            return;
        }
        PreparedRun prepared = prepare(
                task,
                List.copyOf(run.prepared.coordinates.values()),
                List.copyOf(run.prepared.regions.values()),
                inventoryConfig == null ? List.copyOf(run.prepared.categoryZones.values())
                        : taskCategoryZones(task, inventoryConfig),
                run.prepared.warehouses,
                run.prepared.tasks,
                run.prepared.triggers,
                priority,
                false
        );
        QueuedRun created = newQueuedRun(prepared, ticks, "由任务创建，排队等待");
        queue.add(created);
        retain(created.snapshot);
        requestPreemptionIfNeeded(created);
    }

    private void requestPreemptionIfNeeded(QueuedRun queued) {
        if (active == null || active.userCancelRequested) {
            return;
        }
        if (active.interruptionRunning || active.interruptionFailed) {
            return;
        }
        if (!queued.prepared.priority.preempts(active.prepared.priority)) {
            return;
        }
        active.preemptedByRunId = queued.snapshot.runId();
        String message = "被“" + queued.prepared.task.name()
                + "”中断，正在安全停止";
        if (!active.stopRequested) {
            startTermination(TaskRunState.PREEMPTED, message);
        }
    }

    private PreparedRun prepare(
            TaskDefinition task,
            List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions,
            List<CategoryZoneDefinition> categoryZones,
            Map<UUID, CoordinateCatalog.Warehouse> warehouses,
            Map<UUID, TaskDefinition> tasks,
            List<TaskTriggerDefinition> triggerDefinitions,
            TaskPriority priorityOverride,
            boolean allowRepeat
    ) {
        Objects.requireNonNull(task, "task");
        Map<UUID, CoordinatePoint> frozenCoordinates = indexCoordinates(
                coordinates
        );
        Map<UUID, RegionDefinition> frozenRegions = indexRegions(regions);
        Map<UUID, CategoryZoneDefinition> frozenCategoryZones =
                indexCategoryZones(categoryZones);
        Map<UUID, TaskDefinition> frozenTasks = Map.copyOf(tasks);
        List<TaskTriggerDefinition> frozenTriggers = List.copyOf(
                triggerDefinitions
        );
        TaskDefinition compiledTask = TaskInteractionCompiler.compile(
                task,
                frozenTriggers
        );
        TaskPriority priority = priorityOverride == null
                ? compiledTask.priority()
                : priorityOverride;
        boolean repeating = allowRepeat
                && priority == TaskPriority.BACKGROUND
                && compiledTask.repeatWhenIdle();
        TaskDefinition interruptionTask = interruptionTask(
                compiledTask,
                frozenTasks,
                frozenTriggers
        );
        return new PreparedRun(
                compiledTask,
                List.copyOf(compiledTask.steps()),
                frozenCoordinates,
                frozenRegions,
                frozenCategoryZones,
                warehouses,
                frozenTasks,
                frozenTriggers,
                priority,
                repeating,
                interruptionTask
        );
    }

    private static TaskDefinition interruptionTask(
            TaskDefinition task,
            Map<UUID, TaskDefinition> tasks,
            List<TaskTriggerDefinition> triggers
    ) {
        TaskInterruptionMode mode = task.interruptionMode();
        if (mode == TaskInterruptionMode.NONE) {
            return null;
        }
        if (mode == TaskInterruptionMode.CUSTOM) {
            ResourceReference reference = ResourceReference.parse(
                    task.interruptionTaskRef()
            );
            TaskDefinition cleanup = tasks.get(reference.uuid());
            if (cleanup == null) {
                throw new IllegalArgumentException(
                        "interruption task does not exist: " + reference
                );
            }
            return TaskInteractionCompiler.compile(cleanup, triggers);
        }
        List<TaskDefinition> defaults = tasks.values().stream()
                .filter(TaskDefinition::defaultInterruptionFlow)
                .toList();
        if (defaults.size() > 1) {
            throw new IllegalArgumentException(
                    "only one default interruption flow is allowed"
            );
        }
        return defaults.isEmpty()
                ? null
                : TaskInteractionCompiler.compile(defaults.getFirst(), triggers);
    }

    private TaskRunSnapshot startQueued(QueuedRun queued) {
        registry.inventory().reconcileUnassignedItems();
        queue.remove(queued);
        TaskRunSnapshot running = snapshot(
                queued,
                TaskRunState.RUNNING,
                queued.continuation == null ? 0 : queued.continuation.startStep,
                "正在运行",
                Instant.now()
        );
        TaskRunWorkspace workspace = queued.continuation == null ? new TaskRunWorkspace(
                registry.inventory().account(running.runId())) : queued.continuation.workspace;
        if (triggers.collectionTasks().contains(queued.prepared.task.id())) {
            workspace.inventoryAccount().usePickupReceipts(queued.prepared.task.id());
        }
        UUID taskId = queued.prepared.task.id();
        workspace.inventoryAccount().setAcceptsClassification(inventoryConfig != null
                && inventoryConfig.autoClassifyTasks().stream().anyMatch(definition ->
                        definition.enabled() && definition.classificationEnabled()
                                && (definition.classificationTaskId().equals(taskId)
                                || definition.id().equals(taskId))));
        if (inventoryConfig != null) {
            inventoryConfig.autoClassifyTasks().stream().filter(definition ->
                    definition.classificationTaskId().equals(taskId)).findFirst().ifPresent(
                            definition -> workspace.inventoryAccount()
                                    .setRecoveryInputTask(definition.id()));
        }
        workspace.configureCleanup(queued.prepared.task.cleanupPolicy());
        workspace.setDiagnostics(diagnostics);
        diagnostics.append(running.runId(), "TASK", "", "RUN", "STATE", "RUNNING",
                "priority=" + running.priority() + "; resumeStep=" + running.completedSteps(),
                0, false);
        if (queued.continuation != null) workspace.invalidateCacheInventory();
        active = new ActiveRun(
                running,
                queued.prepared,
                workspace,
                executionContext(queued.prepared, workspace)
        );
        active.startStep = queued.continuation == null ? 0 : queued.continuation.startStep;
        active.completedSteps = active.startStep;
        replaceRetained(running);
        return running;
    }

    private TaskRunSnapshot startNextQueued() {
        if (active != null) {
            return null;
        }
        QueuedRun next = queue.stream()
                .filter(run -> run.readyAtTick <= ticks)
                .min(queueOrder())
                .orElse(null);
        return next == null ? null : startQueued(next);
    }

    private void enqueuePrepared(
            PreparedRun prepared,
            int delayTicks,
            String message,
            boolean deduplicate
    ) {
        enqueuePrepared(prepared, delayTicks, message, deduplicate, null);
    }

    private void enqueuePrepared(PreparedRun prepared, int delayTicks, String message,
            boolean deduplicate, Continuation continuation) {
        if (deduplicate) {
            if (active != null
                    && active.prepared.task.id().equals(prepared.task.id())) {
                return;
            }
            if (queue.stream().anyMatch(
                    run -> run.prepared.task.id().equals(prepared.task.id())
            )) {
                return;
            }
        }
        QueuedRun queued = newQueuedRun(
                prepared,
                ticks + delayTicks,
                message
        );
        queued.continuation = continuation;
        queue.add(queued);
        retain(snapshot(
                queued,
                TaskRunState.QUEUED,
                0,
                message,
                Instant.now()
        ));
    }

    private QueuedRun newQueuedRun(
            PreparedRun prepared,
            long readyAtTick,
            String message
    ) {
        Instant now = Instant.now();
        return new QueuedRun(
                UUID.randomUUID(),
                prepared,
                now,
                nextSequence++,
                readyAtTick,
                message
        );
    }

    private TaskExecutionContext executionContext(
            PreparedRun prepared,
            TaskRunWorkspace workspace
    ) {
        workspace.configureTasks(prepared.tasks);
        return new TaskExecutionContext(
                navigation,
                registry.droppedItems(),
                registry.containerRegions(),
                registry.containerTransfers(),
                registry.containerWithdrawals(),
                registry.containerInspections(),
                registry.categoryMarkerCache(),
                registry.foodConsumption(),
                registry.categoryZones(),
                prepared.coordinates,
                prepared.regions,
                prepared.categoryZones,
                workspace,
                registry.inventory(),
                registry.storageAutomation(),
                prepared.warehouses
        );
    }

    private void update(TaskRunState state, String message) {
        update(state, active.completedSteps, message);
    }

    private void update(
            TaskRunState state,
            int completedSteps,
            String message
    ) {
        TaskRunSnapshot old = active.snapshot;
        if (old.state() != state || old.completedSteps() != completedSteps
                || !old.message().equals(message)) {
            diagnostics.append(old.runId(), "TASK", "", "RUN", "STATE", state.name(), message,
                    0, true);
        }
        active.snapshot = new TaskRunSnapshot(
                old.runId(),
                old.taskId(),
                old.taskName(),
                old.priority(),
                old.repeating(),
                state,
                completedSteps,
                old.totalSteps(),
                message,
                old.startedAt(),
                Instant.now()
        );
        replaceRetained(active.snapshot);
    }

    private void publishWorkspaceEvents() {
        if (active == null) {
            return;
        }
        Map<UUID, Integer> updates = active.workspace.consumeCacheUpdates();
        for (Map.Entry<UUID, Integer> update : updates.entrySet()) {
            triggers.cacheUpdated(update.getKey());
            if (inventoryConfig != null) {
                for (AutoClassifyDefinition definition : inventoryConfig.autoClassifyTasks()) {
                    if (definition.enabled() && definition.classificationEnabled()
                            && ResourceReference.parse(definition.cacheLocationRef()).uuid().equals(update.getKey())) {
                        pendingInventoryClassification.add(definition.classificationTaskId());
                    }
                }
            }
        }
    }

    private boolean isTaskPending(UUID taskId) {
        if (active != null && active.prepared.task.id().equals(taskId)) {
            return true;
        }
        return queue.stream().anyMatch(
                run -> run.prepared.task.id().equals(taskId)
        );
    }

    private static Comparator<QueuedRun> queueOrder() {
        return Comparator
                .comparingInt((QueuedRun run) -> run.prepared.priority.rank())
                .reversed()
                .thenComparingLong(run -> run.sequence);
    }

    private static TaskRunSnapshot snapshot(
            QueuedRun queued,
            TaskRunState state,
            int completedSteps,
            String message,
            Instant updatedAt
    ) {
        if (queued.continuation != null) {
            completedSteps = Math.max(completedSteps, queued.continuation.startStep);
        }
        return new TaskRunSnapshot(
                queued.snapshot.runId(),
                queued.prepared.task.id(),
                queued.prepared.task.name(),
                queued.prepared.priority,
                queued.prepared.repeating,
                state,
                completedSteps,
                queued.prepared.steps.size(),
                message,
                queued.snapshot.startedAt(),
                updatedAt
        );
    }

    private void retain(TaskRunSnapshot snapshot) {
        history.addFirst(snapshot);
        trimHistory();
    }

    private void replaceRetained(TaskRunSnapshot snapshot) {
        List<TaskRunSnapshot> values = new ArrayList<>(history);
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).runId().equals(snapshot.runId())) {
                values.set(index, snapshot);
                history.clear();
                history.addAll(values);
                return;
            }
        }
        retain(snapshot);
    }

    private void trimHistory() {
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    private static Map<UUID, CoordinatePoint> indexCoordinates(
            List<CoordinatePoint> coordinates
    ) {
        Map<UUID, CoordinatePoint> result = new LinkedHashMap<>();
        for (CoordinatePoint point : coordinates) {
            result.put(point.id(), point);
        }
        return result;
    }

    private static Map<UUID, RegionDefinition> indexRegions(
            List<RegionDefinition> regions
    ) {
        Map<UUID, RegionDefinition> result = new LinkedHashMap<>();
        for (RegionDefinition region : regions) {
            result.put(region.id(), region);
        }
        return result;
    }

    private static Map<UUID, CategoryZoneDefinition> indexCategoryZones(
            List<CategoryZoneDefinition> categoryZones
    ) {
        Map<UUID, CategoryZoneDefinition> result = new LinkedHashMap<>();
        for (CategoryZoneDefinition zone : categoryZones) {
            result.put(zone.id(), zone);
        }
        return result;
    }

    private static Map<UUID, TaskDefinition> indexKnownTasks(
            List<TaskDefinition> tasks,
            List<TaskTriggerDefinition> triggers
    ) {
        List<TaskDefinition> compiledTasks = new ArrayList<>();
        for (TaskDefinition task : tasks) {
            try {
                compiledTasks.add(TaskInteractionCompiler.compile(task, triggers));
            } catch (TaskInteractionCompiler.DetectorNotBoundException exception) {
                compiledTasks.add(task);
            }
        }
        return indexTasks(compiledTasks);
    }

    private static Map<UUID, TaskDefinition> indexTasks(
            List<TaskDefinition> tasks
    ) {
        Map<UUID, TaskDefinition> result = new LinkedHashMap<>();
        for (TaskDefinition task : tasks) {
            if (result.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("duplicate task id");
            }
        }
        return Map.copyOf(result);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record PreparedRun(
            TaskDefinition task,
            List<TaskStep> steps,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            Map<UUID, CoordinateCatalog.Warehouse> warehouses,
            Map<UUID, TaskDefinition> tasks,
            List<TaskTriggerDefinition> triggers,
            TaskPriority priority,
            boolean repeating,
            TaskDefinition interruptionTask
    ) {
    }

    private static final class QueuedRun {
        private Continuation continuation;
        private final UUID runId;
        private final PreparedRun prepared;
        private final long sequence;
        private final long readyAtTick;
        private final TaskRunSnapshot snapshot;

        private QueuedRun(
                UUID runId,
                PreparedRun prepared,
                Instant requestedAt,
                long sequence,
                long readyAtTick,
                String message
        ) {
            this.runId = runId;
            this.prepared = prepared;
            this.sequence = sequence;
            this.readyAtTick = readyAtTick;
            this.snapshot = new TaskRunSnapshot(
                    runId,
                    prepared.task.id(),
                    prepared.task.name(),
                    prepared.priority,
                    prepared.repeating,
                    TaskRunState.QUEUED,
                    0,
                    prepared.steps.size(),
                    message,
                    requestedAt,
                    requestedAt
            );
        }
    }

    private static final class ActiveRun {
        private int startStep;
        private TaskRunSnapshot snapshot;
        private final PreparedRun prepared;
        private final TaskRunWorkspace workspace;
        private final TaskExecutionContext context;
        private TaskFlowExecutor flow;
        private TaskFlowExecutor interruptionFlow;
        private TaskRunState terminalState = TaskRunState.CANCELLED;
        private String terminalMessage = "任务已取消";
        private String stopWarning = "";
        private UUID preemptedByRunId;
        private boolean stopRequested;
        private boolean userCancelRequested;
        private boolean forceStopRequested;
        private boolean interruptionRunning;
        private boolean interruptionRecovery;
        private boolean retryAfterRecovery;
        private boolean stopFailurePending;
        private boolean interruptionFailed;
        private String interruptionFailureMessage = "";
        private boolean navigationStopRequested;
        private boolean navigationStopConfirmed;
        private int completedSteps;

        private ActiveRun(
                TaskRunSnapshot snapshot,
                PreparedRun prepared,
                TaskRunWorkspace workspace,
                TaskExecutionContext context
        ) {
            this.snapshot = snapshot;
            this.prepared = prepared;
            this.workspace = workspace;
            this.context = context;
        }
    }

    private record Continuation(TaskRunWorkspace workspace, int startStep) {
    }
}
