package dev.stowling.control;

import dev.stowling.automation.AutomationPauseReason;
import dev.stowling.automation.AutomationExecutionStatus;
import dev.stowling.automation.AutomationSnapshot;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.automation.GenericAutomationEngine;
import dev.stowling.automation.GenericAutomationSnapshot;
import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.PickupAutomationPlan;
import dev.stowling.automation.PickupStage;
import dev.stowling.config.ConfigValidator;
import dev.stowling.config.TaskDefinitionStore;
import dev.stowling.schedule.InMemoryTaskScheduleStore;
import dev.stowling.schedule.RegionItemProbe;
import dev.stowling.schedule.RunOutcome;
import dev.stowling.schedule.ScheduledTaskRuntime;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskRuntimeFactory;
import dev.stowling.schedule.TaskScheduleInfo;
import dev.stowling.schedule.TaskScheduleStore;
import dev.stowling.schedule.TaskScheduler;
import dev.stowling.schedule.VersionedTaskDefinition;
import dev.stowling.config.ConfigFileSnapshot;
import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventorySnapshot;
import dev.stowling.inventory.InventorySource;
import dev.stowling.persistence.NestedInventoryScope;
import dev.stowling.persistence.PersistedTask;
import dev.stowling.persistence.SqliteAutomationCheckpointStore;
import dev.stowling.persistence.SqliteGenericAutomationCheckpointStore;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.persistence.TaskRecoveryPlan;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StowlingControlServiceImpl implements StowlingControlService {
    private static final int INVENTORY_RESERVE_SLOTS = 2;
    private static final int MAX_CONTAINER_RETRIES = 3;
    private static final Duration CONTAINER_ACK_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration NAVIGATION_TIMEOUT = Duration.ofMinutes(5);

    private final SqliteTaskRepository tasks;
    private final SqliteInventoryRepository inventories;
    private final InventorySource inventorySource;
    private final PickupRuntimeFactory pickupFactory;
    private final Consumer<Runnable> clientExecutor;
    private final Supplier<StowlingConfig> configReloader;
    private final Consumer<StowlingConfig> configActivated;
    private final ConfigFileStore configFileStore;
    private final AutomationRuntimeFactory automationFactory;

    private StowlingConfig config;
    private ActivePickup activePickup;
    private final Map<UUID, ActiveAutomation> activeAutomations = new LinkedHashMap<>();
    private final TaskDefinitionStore definitions;
    private TaskScheduler scheduler;

    public StowlingControlServiceImpl(
            StowlingConfig config,
            SqliteTaskRepository tasks,
            SqliteInventoryRepository inventories,
            InventorySource inventorySource,
            PickupRuntimeFactory pickupFactory,
            Consumer<Runnable> clientExecutor,
            Supplier<StowlingConfig> configReloader,
            Consumer<StowlingConfig> configActivated,
            ConfigFileStore configFileStore
    ) {
        this(
                config,
                tasks,
                inventories,
                inventorySource,
                pickupFactory,
                clientExecutor,
                configReloader,
                configActivated,
                configFileStore,
                null
        );
    }

    public StowlingControlServiceImpl(
            StowlingConfig config,
            SqliteTaskRepository tasks,
            SqliteInventoryRepository inventories,
            InventorySource inventorySource,
            PickupRuntimeFactory pickupFactory,
            Consumer<Runnable> clientExecutor,
            Supplier<StowlingConfig> configReloader,
            Consumer<StowlingConfig> configActivated,
            ConfigFileStore configFileStore,
            AutomationRuntimeFactory automationFactory
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.inventorySource = Objects.requireNonNull(inventorySource, "inventorySource");
        this.pickupFactory = Objects.requireNonNull(pickupFactory, "pickupFactory");
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.configReloader = Objects.requireNonNull(configReloader, "configReloader");
        this.configActivated = Objects.requireNonNull(configActivated, "configActivated");
        this.configFileStore = Objects.requireNonNull(configFileStore, "configFileStore");
        this.automationFactory = automationFactory;
        this.definitions = new TaskDefinitionStore(configFileStore);
        this.scheduler = newScheduler(
                new InMemoryTaskScheduleStore(),
                regions -> RegionItemProbe.Observation.UNOBSERVABLE,
                Clock.systemUTC()
        );
    }

    @Override
    public synchronized ControlStatus status() {
        String state = scheduler.snapshots().stream()
                .filter(info -> "UNSAFE".equals(info.state()))
                .findFirst().map(info -> "UNSAFE").orElseGet(() -> {
                    if (activePickup != null) {
                        return activePickup.lastSnapshot.stage().name();
                    }
                    if (!activeAutomations.isEmpty()) {
                        return activeAutomations.values().iterator().next()
                                .lastSnapshot.status().name();
                    }
                    return "IDLE";
                });
        return new ControlStatus(false, state, Instant.now());
    }

    @Override
    public synchronized List<TaskInfo> tasks() {
        return tasks.findAll().stream().map(this::toInfo).toList();
    }

    @Override
    public synchronized List<PickupSiteInfo> pickupSites() {
        return config.pickupSites().stream()
                .map(site -> new PickupSiteInfo(
                        site.id(),
                        site.dimension(),
                        new ContainerPosition(
                                site.center().x(),
                                site.center().y(),
                                site.center().z()
                        ),
                        site.radius(),
                        site.idleDelay().toSeconds()
                ))
                .toList();
    }

    @Override
    public synchronized List<PickupWorkflowInfo> pickupWorkflows() {
        Map<String, PickupRegion> regions = regionsById(config);
        return config.pickupWorkflows().stream()
                .map(workflow -> new PickupWorkflowInfo(
                        workflow.id(),
                        workflow.name(),
                        workflow.dimension(),
                        workflow.regions().stream()
                                .map(binding -> workflowRegionInfo(binding, regions))
                                .toList()
                ))
                .toList();
    }

    @Override
    public synchronized TaskInfo requestPickup(PickupRequest request) {
        Objects.requireNonNull(request, "request");
        PickupWorkflow workflow = config.pickupWorkflows().stream()
                .filter(candidate -> candidate.id().equals(request.workflowId()))
                .findFirst().orElseThrow(PickupWorkflowNotFoundException::new);
        validateRegionOverride(workflow, request.regionId());
        if (request.regionId().isPresent()) {
            workflow = new PickupWorkflow(
                    workflow.id(), workflow.name(), workflow.dimension(),
                    workflow.regions().stream().filter(binding ->
                            binding.regionId().equals(request.regionId().orElseThrow())).toList(),
                    workflow.cacheIds(), workflow.cachePolicy(), workflow.classificationTiming(),
                    workflow.completionMode(), workflow.completionIdleDelay(), workflow.settings()
            );
        }
        UUID runId = scheduler.runNow(TaskDefinition.from(workflow));
        return toInfo(tasks.findById(runId).orElseThrow());
    }

    static boolean isDisconnectedPause(AutomationSnapshot snapshot) {
        return snapshot.stage() == PickupStage.PAUSED
                && snapshot.pauseReason()
                        .map(AutomationPauseReason::code)
                        .filter("DISCONNECTED"::equals)
                        .isPresent();
    }

    @Override
    public synchronized TaskInfo cancelTask(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        TaskSnapshot existing = tasks.findById(taskId).orElseThrow(TaskNotFoundException::new);
        if (existing.kind() == TaskKind.PICKUP || existing.kind() == TaskKind.AUTOMATION) {
            scheduler.cancelRun(taskId);
            return toInfo(tasks.findById(taskId).orElseThrow());
        }
        throw new IllegalArgumentException("This execution cannot be cancelled by the scheduler");
    }

    @Override
    public synchronized AuditResult requestAudit(AuditRequest request) {
        Objects.requireNonNull(request, "request");
        if (scheduler.isBusy()) {
            throw new ActiveTaskConflictException();
        }
        Set<String> known = containerIds(config);
        for (String containerId : request.containerIds()) {
            if (!known.contains(containerId)) {
                throw new ContainerNotFoundException();
            }
        }
        Instant now = Instant.now();
        UUID taskId = UUID.randomUUID();
        TaskSnapshot auditTask = new TaskSnapshot(
                taskId,
                TaskKind.AUDIT,
                TaskStatus.RUNNING,
                AutomationState.SORTING,
                now,
                now,
                Optional.empty()
        );
        tasks.save(auditTask);
        AtomicInteger remaining = new AtomicInteger(request.containerIds().size());
        AtomicInteger failures = new AtomicInteger();
        if (remaining.get() == 0) {
            finishAudit(taskId, failures.get());
        }
        for (String containerId : request.containerIds()) {
            ContainerRef container = findContainer(containerId);
            inventorySource.capture(container).whenComplete((capture, error) -> {
                if (error == null && capture != null
                        && capture.snapshot().freshness()
                        == dev.stowling.inventory.InventoryFreshness.FRESH) {
                    persistAuditCapture(container, capture);
                } else {
                    failures.incrementAndGet();
                }
                if (remaining.decrementAndGet() == 0) {
                    finishAudit(taskId, failures.get());
                }
            });
        }
        return new AuditResult(taskId, now);
    }

    @Override
    public synchronized List<InventoryInfo> inventory() {
        List<InventoryInfo> result = new ArrayList<>();
        for (StoredInventorySnapshot stored : inventories.findAllSnapshots()) {
            InventorySnapshot snapshot = stored.snapshot();
            List<InventoryItemInfo> items = snapshot.items().stream()
                    .map(StowlingControlServiceImpl::toInfo)
                    .toList();
            result.add(new InventoryInfo(
                    snapshot.sourceId(),
                    snapshot.containerId(),
                    snapshot.dimension(),
                    snapshot.capturedAt(),
                    snapshot.freshness().name(),
                    items
            ));
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<ContainerInfo> containers() {
        return allContainers(config).stream()
                .map(container -> new ContainerInfo(
                        container.id(),
                        container.dimension(),
                        new ContainerPosition(
                                container.position().x(),
                                container.position().y(),
                                container.position().z()
                        ),
                        container.type().name()
                ))
                .toList();
    }

    @Override
    public synchronized List<AutomationModuleInfo> automationModules() {
        if (automationFactory == null) {
            return List.of();
        }
        return automationFactory.modules().stream()
                .map(AutomationModuleInfo::from)
                .toList();
    }

    @Override
    public synchronized TaskInfo requestAutomation(AutomationTaskRequest request) {
        Objects.requireNonNull(request, "request");
        AutomationWorkflow workflow = config.automationWorkflows().stream()
                .filter(candidate -> candidate.id().equals(request.workflowId()))
                .findFirst().orElseThrow(AutomationWorkflowNotFoundException::new);
        requireAutomationRuntime();
        UUID runId = scheduler.runNow(TaskDefinition.from(workflow));
        return toInfo(tasks.findById(runId).orElseThrow());
    }

    @Override
    public synchronized AutomationWorkflowsSnapshot automationWorkflows() {
        return toAutomationWorkflowSnapshot(configFileStore.read());
    }

    @Override
    public synchronized AutomationWorkflowsSnapshot updateAutomationWorkflows(
            AutomationWorkflowUpdateRequest request
    ) {
        synchronized (configFileStore) {
            Objects.requireNonNull(request, "request");
            if (hasBlockingMinecraftTask()) {
                throw new ActiveTaskConflictException();
            }
            ConfigFileSnapshot current = configFileStore.read();
            if (!current.revision().equals(request.revision())) {
                throw new ConfigRevisionConflictException();
            }
            StowlingConfig source = current.config();
            StowlingConfig updated = new StowlingConfig(
                    StowlingConfig.CURRENT_SCHEMA_VERSION,
                    source.coordinatePoints(),
                    source.pickupRegions(),
                    source.cacheContainers(),
                    source.pickupWorkflows(),
                    request.automationWorkflows(),
                    source.storageRoutes(),
                    source.packingStations(),
                    source.auditSchedules(),
                    source.overflowContainer()
            );
            try {
                ConfigValidator.requireValid(updated);
            } catch (RuntimeException exception) {
                throw new InvalidAutomationConfigException(exception.getMessage());
            }
            ConfigFileSnapshot saved = configFileStore.save(request.revision(), updated);
            configActivated.accept(saved.config());
            config = saved.config();
            return toAutomationWorkflowSnapshot(saved);
        }
    }

    @Override
    public synchronized ControlStatus pause() {
        throw new UnsupportedOperationException("Pause was removed; disable the task instead");
    }

    @Override
    public synchronized ControlStatus resume() {
        throw new UnsupportedOperationException("Resume was removed; run the task from the start");
    }

    @Override
    public synchronized ConfigReloadResult reloadConfig() {
        if (hasBlockingMinecraftTask()) {
            throw new ActiveTaskConflictException();
        }
        StowlingConfig loaded = configReloader.get();
        ConfigValidator.requireValid(loaded);
        configActivated.accept(loaded);
        config = loaded;
        return new ConfigReloadResult(true, Instant.now());
    }

    @Override
    public synchronized ConfigCoordinatePointsSnapshot coordinatePoints() {
        return toCoordinatePointsSnapshot(configFileStore.read());
    }

    @Override
    public synchronized ConfigCoordinatePointsSnapshot updateCoordinatePoints(
            ConfigCoordinatePointsUpdateRequest request
    ) {
        synchronized (configFileStore) {
            Objects.requireNonNull(request, "request");
            if (hasBlockingMinecraftTask()) {
                throw new ActiveTaskConflictException();
            }
            ConfigFileSnapshot current = configFileStore.read();
            if (!current.revision().equals(request.revision())) {
                throw new ConfigRevisionConflictException();
            }
            List<NamedCoordinate> points = namedCoordinates(request.coordinatePoints());
            Map<String, NamedCoordinate> pointsById = indexCoordinates(points);
            rejectReferencedCoordinateDeletions(current.config(), pointsById);
            StowlingConfig updated;
            try {
                updated = withSynchronizedCoordinateResources(
                        current.config(),
                        points,
                        pointsById
                );
                ConfigValidator.requireValid(updated);
            } catch (IllegalArgumentException exception) {
                throw new InvalidCoordinatePointsException(exception.getMessage());
            }
            ConfigFileSnapshot saved = configFileStore.save(request.revision(), updated);
            configActivated.accept(saved.config());
            config = saved.config();
            return toCoordinatePointsSnapshot(saved);
        }
    }

    @Override
    public synchronized ConfigCoordinatesSnapshot configCoordinates() {
        return toCoordinateSnapshot(configFileStore.read());
    }

    @Override
    public synchronized ConfigCoordinatesSnapshot updateConfigCoordinates(
            ConfigCoordinatesUpdateRequest request
    ) {
        synchronized (configFileStore) {
            Objects.requireNonNull(request, "request");
            if (hasBlockingMinecraftTask()) {
                throw new ActiveTaskConflictException();
            }
            ConfigFileSnapshot current = configFileStore.read();
            if (!current.revision().equals(request.revision())) {
                throw new ConfigRevisionConflictException();
            }
            List<ConfigCoordinateInfo> currentCoordinates = coordinateInfos(current.config());
            Map<String, BlockPosition> updates = validateCoordinateUpdates(
                    currentCoordinates,
                    request.coordinates()
            );
            StowlingConfig updated = withCoordinates(current.config(), updates);
            ConfigFileSnapshot saved = configFileStore.save(request.revision(), updated);
            configActivated.accept(saved.config());
            config = saved.config();
            return toCoordinateSnapshot(saved);
        }
    }

    @Override
    public synchronized PickupConfigSnapshot pickupConfig() {
        return toPickupConfigSnapshot(configFileStore.read());
    }

    @Override
    public synchronized PickupConfigSnapshot updatePickupConfig(
            PickupConfigUpdateRequest request
    ) {
        synchronized (configFileStore) {
            Objects.requireNonNull(request, "request");
            if (hasBlockingMinecraftTask()) {
                throw new ActiveTaskConflictException();
            }
            ConfigFileSnapshot current = configFileStore.read();
            if (!current.revision().equals(request.revision())) {
                throw new ConfigRevisionConflictException();
            }
            StowlingConfig source = current.config();
            StowlingConfig updated;
            try {
                validatePickupReferences(request);
                updated = withSynchronizedPickupResources(source, request);
                ConfigValidator.requireValid(updated);
            } catch (IllegalArgumentException exception) {
                throw new InvalidPickupConfigException(exception.getMessage());
            }
            ConfigFileSnapshot saved = configFileStore.save(request.revision(), updated);
            configActivated.accept(saved.config());
            config = saved.config();
            return toPickupConfigSnapshot(saved);
        }
    }

    public synchronized void tickOnClientThread() {
        scheduler.tick();
    }

    public synchronized void recoverPendingPickups() {
        // Old clicks and checkpoints belong to previous executions and are never replayed.
        for (TaskRecoveryPlan plan : tasks.findRecoveryPlans()) {
            failTask(plan.taskId(), "previous execution ended on restart; checkpoint discarded");
        }
        scheduler.restore();
    }

    @Override
    public synchronized PickupConfigSnapshot taskResources() {
        return toPickupConfigSnapshot(configFileStore.read());
    }

    @Override
    public synchronized PickupConfigSnapshot updateTaskResources(PickupConfigUpdateRequest request) {
        synchronized (configFileStore) {
            if (scheduler.isBusy()) {
                throw new ActiveTaskConflictException();
            }
            ConfigFileSnapshot current = configFileStore.read();
            if (!current.revision().equals(request.revision())) {
                throw new ConfigRevisionConflictException();
            }
            PickupConfigUpdateRequest resourcesOnly = new PickupConfigUpdateRequest(
                    request.revision(), request.pickupRegions(), request.cacheContainers(),
                    current.config().pickupWorkflows()
            );
            StowlingConfig updated = withSynchronizedPickupResources(current.config(), resourcesOnly);
            ConfigValidator.requireValid(updated);
            ConfigFileSnapshot saved = configFileStore.save(current.revision(), updated);
            configActivated.accept(saved.config());
            config = saved.config();
            return toPickupConfigSnapshot(saved);
        }
    }

    public synchronized void configureScheduling(
            TaskScheduleStore store, RegionItemProbe probe, Clock clock
    ) {
        if (scheduler.isBusy()) {
            throw new ActiveTaskConflictException();
        }
        scheduler = newScheduler(store, probe, clock);
    }

    @Override
    public synchronized List<TaskDefinition> taskDefinitions() {
        return currentDefinitions();
    }

    @Override
    public synchronized VersionedTaskDefinition taskDefinition(TaskKey key) {
        return definitions.get(key);
    }

    @Override
    public synchronized VersionedTaskDefinition createTaskDefinition(TaskDefinition definition) {
        synchronized (configFileStore) {
            VersionedTaskDefinition saved = definitions.create(definition);
            activateSavedConfig();
            return saved;
        }
    }

    @Override
    public synchronized VersionedTaskDefinition updateTaskDefinition(
            TaskKey key, String revision, TaskDefinition definition
    ) {
        synchronized (configFileStore) {
            VersionedTaskDefinition saved = definitions.update(key, revision, definition);
            activateSavedConfig();
            return saved;
        }
    }

    @Override
    public synchronized void deleteTaskDefinition(TaskKey key, String revision) {
        synchronized (configFileStore) {
            definitions.delete(key, revision);
            scheduler.disable(key);
            activateSavedConfig();
        }
    }

    @Override
    public synchronized List<TaskScheduleInfo> taskScheduling() {
        return scheduler.snapshots();
    }

    @Override
    public synchronized void enableTaskDefinition(TaskKey key) {
        definitions.get(key);
        scheduler.enable(key);
    }

    @Override
    public synchronized void disableTaskDefinition(TaskKey key) {
        definitions.get(key);
        scheduler.disable(key);
    }

    @Override
    public synchronized TaskInfo runTaskDefinition(TaskKey key) {
        definitions.get(key);
        if (TaskKey.AUTOMATION.equals(key.kind())) {
            requireAutomationRuntime();
        }
        UUID runId = scheduler.runNow(key);
        return toInfo(tasks.findById(runId).orElseThrow());
    }

    private void activateSavedConfig() {
        StowlingConfig saved = configFileStore.read().config();
        configActivated.accept(saved);
        config = saved;
    }

    public synchronized StowlingConfig activeConfig() {
        return config;
    }

    private List<TaskDefinition> currentDefinitions() {
        List<TaskDefinition> result = new ArrayList<>();
        config.pickupWorkflows().stream().map(TaskDefinition::from).forEach(result::add);
        config.automationWorkflows().stream().map(TaskDefinition::from).forEach(result::add);
        return List.copyOf(result);
    }

    private void requireAutomationRuntime() {
        if (automationFactory == null) {
            throw new IllegalStateException("Automation runtime is unavailable");
        }
    }

    private TaskScheduler newScheduler(TaskScheduleStore store, RegionItemProbe probe, Clock clock) {
        return new TaskScheduler(this::currentDefinitions, new TaskRuntimeFactory() {
            @Override
            public void queued(UUID runId, TaskDefinition definition) {
                saveQueuedRun(runId, definition);
            }

            @Override
            public ScheduledTaskRuntime start(UUID runId, TaskDefinition definition) {
                return startScheduledRuntime(runId, definition);
            }

            @Override
            public void finished(
                    UUID runId, TaskDefinition definition, String status, String message
            ) {
                finishScheduledRun(runId, status, message);
            }
        }, probe, store, clock);
    }

    private void saveQueuedRun(UUID runId, TaskDefinition definition) {
        if (tasks.findById(runId).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        tasks.save(new TaskSnapshot(
                runId, TaskKind.valueOf(definition.kind()), TaskStatus.PENDING,
                AutomationState.IDLE_WAITING, now, now, Optional.empty()
        ));
        tasks.saveRunDefinition(runId, definition);
    }

    private ScheduledTaskRuntime startScheduledRuntime(UUID runId, TaskDefinition definition) {
        saveQueuedRun(runId, definition);
        if (TaskKey.PICKUP.equals(definition.kind())) {
            PickupWorkflow workflow = definition.toPickup();
            PickupAutomationEngine engine = pickupFactory.create(plan(runId, workflow,
                    Optional.empty()));
            ActivePickup active = new ActivePickup(runId, workflow.id(), Optional.empty(),
                    engine, engine.snapshot());
            activePickup = active;
            engine.start();
            return new ScheduledTaskRuntime() {
                private RunOutcome terminal;

                @Override
                public RunOutcome tick() {
                    if (terminal != null) {
                        return cleanCompletion(terminal, engine.pollStop());
                    }
                    active.lastSnapshot = engine.tick();
                    persistSnapshot(active, active.lastSnapshot);
                    PickupStage stage = active.lastSnapshot.stage();
                    if (isTerminal(stage) || stage == PickupStage.PAUSED) {
                        terminal = new RunOutcome(stage == PickupStage.COMPLETE
                                ? RunOutcome.SUCCEEDED : RunOutcome.FAILED,
                                active.lastSnapshot.pauseReason()
                                        .map(StowlingControlServiceImpl::formatPause).orElse(""));
                        engine.requestStop();
                        return cleanCompletion(terminal, engine.pollStop());
                    }
                    return new RunOutcome(RunOutcome.RUNNING, stage.name());
                }

                @Override
                public void requestStop() {
                    engine.requestStop();
                }

                @Override
                public StopOutcome pollStop() {
                    return engine.pollStop();
                }
            };
        }
        requireAutomationRuntime();
        AutomationWorkflow workflow = definition.toAutomation();
        GenericAutomationEngine engine = automationFactory.create(runId, workflow);
        ActiveAutomation active = new ActiveAutomation(runId, workflow, engine, engine.snapshot());
        activeAutomations.put(runId, active);
        engine.start();
        return new ScheduledTaskRuntime() {
            private RunOutcome terminal;

            @Override
            public RunOutcome tick() {
                if (terminal != null) {
                    return cleanCompletion(terminal, engine.pollStop());
                }
                active.lastSnapshot = engine.tick();
                persistAutomationSnapshot(active, active.lastSnapshot);
                AutomationExecutionStatus status = active.lastSnapshot.status();
                if (isTerminal(status) || status == AutomationExecutionStatus.PAUSED) {
                    terminal = new RunOutcome(status == AutomationExecutionStatus.SUCCEEDED
                            ? RunOutcome.SUCCEEDED : RunOutcome.FAILED,
                            active.lastSnapshot.message().orElse(""));
                    engine.requestStop();
                    return cleanCompletion(terminal, engine.pollStop());
                }
                return new RunOutcome(RunOutcome.RUNNING, status.name());
            }

            @Override
            public void requestStop() {
                engine.requestStop();
            }

            @Override
            public StopOutcome pollStop() {
                return engine.pollStop();
            }
        };
    }

    private static RunOutcome cleanCompletion(RunOutcome terminal, StopOutcome stop) {
        return switch (stop) {
            case CLEAN -> terminal;
            case PENDING -> new RunOutcome(RunOutcome.RUNNING, "waiting for clean stop");
            case UNSAFE -> new RunOutcome(RunOutcome.UNSAFE,
                    "异常待处理：无法确认安全退出");
        };
    }

    private void finishScheduledRun(UUID runId, String status, String message) {
        TaskSnapshot existing = tasks.findById(runId).orElseThrow();
        TaskStatus finishedStatus = switch (status) {
            case RunOutcome.SUCCEEDED -> TaskStatus.SUCCEEDED;
            case RunOutcome.FAILED, RunOutcome.UNSAFE -> TaskStatus.FAILED;
            default -> TaskStatus.CANCELLED;
        };
        String detail = "INTERRUPTED".equals(status)
                ? "Interrupted; next attempt restarts from the beginning. "
                        + (message == null ? "" : message)
                : message;
        tasks.save(new TaskSnapshot(
                runId, existing.kind(), finishedStatus,
                finishedStatus == TaskStatus.FAILED ? AutomationState.FAILED
                        : finishedStatus == TaskStatus.SUCCEEDED ? AutomationState.COMPLETED
                                : AutomationState.IDLE_WAITING,
                existing.createdAt(), Instant.now(),
                detail == null || detail.isBlank() ? Optional.empty() : Optional.of(detail)
        ));
        // An unsafe runtime still owns the robot; retain it for operator inspection.
        if (!RunOutcome.UNSAFE.equals(status)) {
            if (activePickup != null && activePickup.taskId.equals(runId)) {
                activePickup = null;
            }
            activeAutomations.remove(runId);
        }
    }

    private PickupAutomationPlan plan(
            UUID taskId,
            PickupWorkflow workflow,
            Optional<String> regionOverride
    ) {
        return new PickupAutomationPlan(
                taskId,
                workflow,
                regionOverride,
                regionsById(config),
                cachesById(config),
                config.storageRoutes(),
                config.overflowContainer(),
                INVENTORY_RESERVE_SLOTS,
                MAX_CONTAINER_RETRIES,
                CONTAINER_ACK_TIMEOUT,
                NAVIGATION_TIMEOUT
        );
    }

    private boolean hasBlockingMinecraftTask() {
        return scheduler.isBusy();
    }

    private static void validateRegionOverride(
            PickupWorkflow workflow,
            Optional<String> regionId
    ) {
        if (regionId.isPresent() && workflow.regions().stream().noneMatch(
                binding -> binding.regionId().equals(regionId.orElseThrow())
        )) {
            throw new InvalidPickupRequestException(
                    "Region does not belong to pickup workflow: "
                            + regionId.orElseThrow()
            );
        }
    }

    private static void validatePickupReferences(PickupConfigUpdateRequest request) {
        Set<String> regionIds = request.pickupRegions().stream()
                .map(PickupRegion::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> cacheIds = request.cacheContainers().stream()
                .map(NamedCache::id)
                .collect(java.util.stream.Collectors.toSet());
        for (PickupWorkflow workflow : request.pickupWorkflows()) {
            for (PickupRegionBinding binding : workflow.regions()) {
                if (!regionIds.contains(binding.regionId())) {
                    throw new InvalidPickupConfigException(
                            "Pickup region '" + binding.regionId()
                                    + "' is referenced by workflow '"
                                    + workflow.id() + "'"
                    );
                }
            }
            for (String cacheId : workflow.cacheIds()) {
                if (!cacheIds.contains(cacheId)) {
                    throw new InvalidPickupConfigException(
                            "Cache '" + cacheId + "' is referenced by workflow '"
                                    + workflow.id() + "'"
                    );
                }
            }
        }
    }

    private static StowlingConfig withSynchronizedPickupResources(
            StowlingConfig source,
            PickupConfigUpdateRequest request
    ) {
        Map<String, NamedCoordinate> pointsById = indexCoordinates(
                source.coordinatePoints()
        );
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                source.coordinatePoints(),
                synchronizedRegions(request.pickupRegions(), pointsById),
                synchronizedCaches(request.cacheContainers(), pointsById),
                request.pickupWorkflows(),
                source.automationWorkflows(),
                source.storageRoutes(),
                source.packingStations(),
                source.auditSchedules(),
                source.overflowContainer()
        );
    }

    private static StowlingConfig withSynchronizedCoordinateResources(
            StowlingConfig source,
            List<NamedCoordinate> coordinates,
            Map<String, NamedCoordinate> pointsById
    ) {
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                synchronizedRegions(source.pickupRegions(), pointsById),
                synchronizedCaches(source.cacheContainers(), pointsById),
                source.pickupWorkflows(),
                source.automationWorkflows(),
                source.storageRoutes(),
                source.packingStations(),
                source.auditSchedules(),
                source.overflowContainer()
        );
    }

    private static List<PickupRegion> synchronizedRegions(
            List<PickupRegion> regions,
            Map<String, NamedCoordinate> pointsById
    ) {
        List<PickupRegion> result = new ArrayList<>();
        for (PickupRegion region : regions) {
            String owner = "pickup region '" + region.id() + "'";
            NamedCoordinate standby = requiredCoordinate(
                    pointsById,
                    region.standbyCoordinateId(),
                    owner + " standby"
            );
            result.add(new PickupRegion(
                    region.id(),
                    region.name(),
                    standby.dimension(),
                    standby.id(),
                    standby.position(),
                    synchronizedShape(region.shape(), pointsById, owner)
            ));
        }
        return List.copyOf(result);
    }

    private static PickupShape synchronizedShape(
            PickupShape shape,
            Map<String, NamedCoordinate> pointsById,
            String owner
    ) {
        if (shape instanceof SpherePickupShape sphere) {
            NamedCoordinate center = requiredCoordinate(
                    pointsById,
                    sphere.centerCoordinateId(),
                    owner + " center"
            );
            return new SpherePickupShape(
                    center.id(),
                    center.position(),
                    sphere.radius()
            );
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) shape;
        NamedCoordinate first = requiredCoordinate(
                pointsById,
                cuboid.firstCornerCoordinateId(),
                owner + " first corner"
        );
        NamedCoordinate second = requiredCoordinate(
                pointsById,
                cuboid.secondCornerCoordinateId(),
                owner + " second corner"
        );
        return CuboidPickupShape.fromCoordinates(first, second);
    }

    private static List<NamedCache> synchronizedCaches(
            List<NamedCache> caches,
            Map<String, NamedCoordinate> pointsById
    ) {
        List<NamedCache> result = new ArrayList<>();
        for (NamedCache cache : caches) {
            NamedCoordinate coordinate = requiredCoordinate(
                    pointsById,
                    cache.coordinateId(),
                    "cache '" + cache.id() + "'"
            );
            result.add(new NamedCache(
                    cache.id(),
                    cache.name(),
                    coordinate.id(),
                    coordinate.dimension(),
                    coordinate.position(),
                    cache.type()
            ));
        }
        return List.copyOf(result);
    }

    private static NamedCoordinate requiredCoordinate(
            Map<String, NamedCoordinate> pointsById,
            String coordinateId,
            String owner
    ) {
        if (coordinateId == null || coordinateId.isBlank()) {
            throw new InvalidCoordinatePointsException(
                    owner + " requires a coordinate reference"
            );
        }
        NamedCoordinate coordinate = pointsById.get(coordinateId);
        if (coordinate == null) {
            throw new InvalidCoordinatePointsException(
                    owner + " references unknown coordinate: " + coordinateId
            );
        }
        return coordinate;
    }

    private static List<NamedCoordinate> namedCoordinates(
            List<ConfigCoordinatePointInfo> points
    ) {
        return points.stream()
                .map(point -> new NamedCoordinate(
                        point.id(),
                        point.name(),
                        point.dimension(),
                        new BlockPosition(
                                point.position().x(),
                                point.position().y(),
                                point.position().z()
                        )
                ))
                .toList();
    }

    private static Map<String, NamedCoordinate> indexCoordinates(
            List<NamedCoordinate> points
    ) {
        Map<String, NamedCoordinate> result = new LinkedHashMap<>();
        for (NamedCoordinate point : points) {
            NamedCoordinate previous = result.putIfAbsent(point.id(), point);
            if (previous != null) {
                throw new InvalidCoordinatePointsException(
                        "Duplicate coordinate point id: " + point.id()
                );
            }
        }
        return Map.copyOf(result);
    }

    private static void rejectReferencedCoordinateDeletions(
            StowlingConfig source,
            Map<String, NamedCoordinate> remaining
    ) {
        for (NamedCoordinate coordinate : source.coordinatePoints()) {
            if (remaining.containsKey(coordinate.id())) {
                continue;
            }
            Set<String> regionIds = new LinkedHashSet<>();
            for (PickupRegion region : source.pickupRegions()) {
                if (referencesCoordinate(region, coordinate.id())) {
                    regionIds.add(region.id());
                }
            }
            Set<String> cacheIds = new LinkedHashSet<>();
            for (NamedCache cache : source.cacheContainers()) {
                if (coordinate.id().equals(cache.coordinateId())) {
                    cacheIds.add(cache.id());
                }
            }
            Set<String> automationWorkflowIds = new LinkedHashSet<>();
            for (AutomationWorkflow workflow : source.automationWorkflows()) {
                boolean references = workflow.steps().stream()
                        .anyMatch(step -> referencesCoordinate(step, coordinate.id()))
                        || workflow.settings().interruption().steps().stream()
                        .anyMatch(step -> referencesCoordinate(step, coordinate.id()));
                if (references) {
                    automationWorkflowIds.add(workflow.id());
                }
            }
            for (PickupWorkflow workflow : source.pickupWorkflows()) {
                if (workflow.settings().interruption().steps().stream()
                        .anyMatch(step -> referencesCoordinate(step, coordinate.id()))) {
                    automationWorkflowIds.add(workflow.id());
                }
            }
            if (!regionIds.isEmpty()
                    || !cacheIds.isEmpty()
                    || !automationWorkflowIds.isEmpty()) {
                throw new InvalidCoordinatePointsException(
                        referencedCoordinateMessage(
                                coordinate.id(),
                                regionIds,
                                cacheIds,
                                automationWorkflowIds
                        )
                );
            }
        }
    }

    private static boolean referencesCoordinate(AutomationStep step, String coordinateId) {
        return List.of(
                "coordinateId", "standbyCoordinateId", "firstCornerCoordinateId",
                "secondCornerCoordinateId", "sourceCoordinateId", "destinationCoordinateId",
                "boxCoordinateId", "outputCoordinateId", "fallbackCoordinateId"
        ).stream().anyMatch(key -> coordinateId.equals(step.parameter(key)));
    }

    private static boolean referencesCoordinate(PickupRegion region, String coordinateId) {
        if (coordinateId.equals(region.standbyCoordinateId())) {
            return true;
        }
        if (region.shape() instanceof SpherePickupShape sphere) {
            return coordinateId.equals(sphere.centerCoordinateId());
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) region.shape();
        return coordinateId.equals(cuboid.firstCornerCoordinateId())
                || coordinateId.equals(cuboid.secondCornerCoordinateId());
    }

    private static String referencedCoordinateMessage(
            String coordinateId,
            Set<String> regionIds,
            Set<String> cacheIds,
            Set<String> automationWorkflowIds
    ) {
        List<String> references = new ArrayList<>();
        if (!regionIds.isEmpty()) {
            references.add("pickup regions " + regionIds);
        }
        if (!cacheIds.isEmpty()) {
            references.add("caches " + cacheIds);
        }
        if (!automationWorkflowIds.isEmpty()) {
            references.add("automation workflows " + automationWorkflowIds);
        }
        return "Coordinate '" + coordinateId + "' cannot be deleted; referenced by "
                + String.join(" and ", references);
    }

    private static Map<String, PickupRegion> regionsById(StowlingConfig config) {
        Map<String, PickupRegion> result = new LinkedHashMap<>();
        for (PickupRegion region : config.pickupRegions()) {
            result.put(region.id(), region);
        }
        return Map.copyOf(result);
    }

    private static Map<String, ContainerRef> cachesById(StowlingConfig config) {
        Map<String, ContainerRef> result = new LinkedHashMap<>();
        for (NamedCache cache : config.cacheContainers()) {
            result.put(cache.id(), cache.toContainerRef());
        }
        return Map.copyOf(result);
    }

    private static PickupWorkflowRegionInfo workflowRegionInfo(
            PickupRegionBinding binding,
            Map<String, PickupRegion> regions
    ) {
        PickupRegion region = regions.get(binding.regionId());
        if (region == null) {
            throw new IllegalStateException("Pickup workflow references an unknown region");
        }
        return new PickupWorkflowRegionInfo(
                region.id(),
                region.name(),
                binding.priority(),
                binding.idleDelay().toSeconds()
        );
    }

    private void persistSnapshot(ActivePickup active, AutomationSnapshot snapshot) {
        PersistedTask current = tasks.findRecordById(active.taskId).orElseThrow();
        TaskSnapshot existing = current.snapshot();
        TaskStatus status = SqliteAutomationCheckpointStore.statusOf(snapshot.stage());
        AutomationState state = SqliteAutomationCheckpointStore.automationStateOf(
                snapshot.stage()
        );
        Optional<String> failure = snapshot.pauseReason().map(
                StowlingControlServiceImpl::formatPause
        );
        TaskSnapshot updated = new TaskSnapshot(
                existing.id(),
                existing.kind(),
                status,
                state,
                existing.createdAt(),
                Instant.now(),
                failure
        );
        Optional<String> checkpoint = isTerminal(snapshot.stage())
                ? Optional.empty()
                : current.recoveryCheckpoint();
        tasks.save(new PersistedTask(updated, checkpoint, current.retryCount()));
    }

    private void persistAutomationSnapshot(
            ActiveAutomation active,
            GenericAutomationSnapshot snapshot
    ) {
        PersistedTask current = tasks.findRecordById(active.taskId).orElseThrow();
        TaskSnapshot existing = current.snapshot();
        Optional<String> failure = snapshot.status() == AutomationExecutionStatus.FAILED
                ? snapshot.message()
                : Optional.empty();
        TaskSnapshot updated = new TaskSnapshot(
                existing.id(),
                existing.kind(),
                SqliteGenericAutomationCheckpointStore.statusOf(snapshot.status()),
                SqliteGenericAutomationCheckpointStore.automationStateOf(snapshot.status()),
                existing.createdAt(),
                Instant.now(),
                failure
        );
        tasks.save(new PersistedTask(
                updated,
                current.recoveryCheckpoint(),
                current.retryCount()
        ));
    }

    private void failTask(UUID taskId, String reason) {
        TaskSnapshot existing = tasks.findById(taskId).orElseThrow();
        TaskSnapshot failed = new TaskSnapshot(
                existing.id(),
                existing.kind(),
                TaskStatus.FAILED,
                AutomationState.FAILED,
                existing.createdAt(),
                Instant.now(),
                Optional.of(reason)
        );
        tasks.save(failed);
    }

    private void persistAuditCapture(ContainerRef container, InventoryCapture capture) {
        List<NestedInventoryScope> nestedScopes = capture.nestedScopes().stream()
                .map(scope -> new NestedInventoryScope(
                scope.scopeId(),
                scope.parentScopeId(),
                scope.items()
        ))
                .toList();
        inventories.replaceSnapshot(
                container,
                capture.snapshot(),
                Instant.now(),
                nestedScopes
        );
    }

    private void finishAudit(UUID taskId, int failures) {
        TaskSnapshot existing = tasks.findById(taskId).orElseThrow();
        boolean succeeded = failures == 0;
        TaskSnapshot finished = new TaskSnapshot(
                existing.id(),
                existing.kind(),
                succeeded ? TaskStatus.SUCCEEDED : TaskStatus.FAILED,
                succeeded ? AutomationState.COMPLETED : AutomationState.FAILED,
                existing.createdAt(),
                Instant.now(),
                succeeded ? Optional.empty() : Optional.of("one or more container audits failed")
        );
        tasks.save(finished);
    }

    private ContainerRef findContainer(String containerId) {
        return allContainers(config).stream()
                .filter(container -> container.id().equals(containerId))
                .findFirst()
                .orElseThrow(ContainerNotFoundException::new);
    }

    private static Set<String> containerIds(StowlingConfig config) {
        Set<String> ids = new HashSet<>();
        for (ContainerRef container : allContainers(config)) {
            ids.add(container.id());
        }
        return Set.copyOf(ids);
    }

    private static List<ContainerRef> allContainers(StowlingConfig config) {
        List<ContainerRef> result = new ArrayList<>();
        result.add(config.overflowContainer());
        for (NamedCache cache : config.cacheContainers()) {
            result.add(cache.toContainerRef());
        }
        for (StorageRoute route : config.storageRoutes()) {
            result.add(route.bulkContainer());
        }
        for (PackingStation station : config.packingStations()) {
            result.add(station.productContainer());
        }
        return List.copyOf(result);
    }

    private static ConfigCoordinatesSnapshot toCoordinateSnapshot(ConfigFileSnapshot snapshot) {
        return new ConfigCoordinatesSnapshot(
                snapshot.revision(),
                coordinateInfos(snapshot.config())
        );
    }

    private static AutomationWorkflowsSnapshot toAutomationWorkflowSnapshot(
            ConfigFileSnapshot snapshot
    ) {
        return new AutomationWorkflowsSnapshot(
                snapshot.revision(),
                snapshot.config().automationWorkflows()
        );
    }

    private static ConfigCoordinatePointsSnapshot toCoordinatePointsSnapshot(
            ConfigFileSnapshot snapshot
    ) {
        List<ConfigCoordinatePointInfo> points = new ArrayList<>();
        for (NamedCoordinate point : snapshot.config().coordinatePoints()) {
            ContainerPosition position = new ContainerPosition(
                    point.position().x(),
                    point.position().y(),
                    point.position().z()
            );
            points.add(new ConfigCoordinatePointInfo(
                    point.id(),
                    point.name(),
                    point.dimension(),
                    position
            ));
        }
        return new ConfigCoordinatePointsSnapshot(snapshot.revision(), points);
    }

    private static PickupConfigSnapshot toPickupConfigSnapshot(
            ConfigFileSnapshot snapshot
    ) {
        StowlingConfig value = snapshot.config();
        return new PickupConfigSnapshot(
                snapshot.revision(),
                value.pickupRegions(),
                value.cacheContainers(),
                value.pickupWorkflows()
        );
    }

    private static List<ConfigCoordinateInfo> coordinateInfos(StowlingConfig config) {
        LinkedHashMap<String, ConfigCoordinateInfo> result = new LinkedHashMap<>();
        Set<String> pickupCacheIds = config.cacheContainers().stream()
                .map(NamedCache::id)
                .collect(java.util.stream.Collectors.toSet());
        for (StorageRoute route : config.storageRoutes()) {
            ContainerRef container = route.bulkContainer();
            registerContainer(
                    result,
                    container,
                    "Storage container " + container.id(),
                    pickupCacheIds
            );
        }
        for (PackingStation station : config.packingStations()) {
            String key = "packing-station/" + station.id() + "/shulker-box";
            result.put(key, coordinateInfo(
                    key,
                    "PACKING_STATION_SHULKER_BOX",
                    station.id(),
                    "Packing station " + station.id(),
                    station.dimension(),
                    station.shulkerBoxPosition()
            ));
            ContainerRef container = station.productContainer();
            registerContainer(
                    result,
                    container,
                    "Product container " + container.id(),
                    pickupCacheIds
            );
        }
        ContainerRef overflow = config.overflowContainer();
        registerContainer(
                result,
                overflow,
                "Overflow container " + overflow.id(),
                pickupCacheIds
        );
        return List.copyOf(result.values());
    }

    private static void registerContainer(
            Map<String, ConfigCoordinateInfo> coordinates,
            ContainerRef container,
            String label,
            Set<String> excludedIds
    ) {
        if (excludedIds.contains(container.id())) {
            return;
        }
        String key = "container/" + container.id();
        coordinates.putIfAbsent(key, coordinateInfo(
                key,
                "CONTAINER",
                container.id(),
                label,
                container.dimension(),
                container.position()
        ));
    }

    private static ConfigCoordinateInfo coordinateInfo(
            String key,
            String kind,
            String ownerId,
            String label,
            String dimension,
            BlockPosition position
    ) {
        return new ConfigCoordinateInfo(
                key,
                kind,
                ownerId,
                label,
                dimension,
                new ContainerPosition(position.x(), position.y(), position.z())
        );
    }

    private static Map<String, BlockPosition> validateCoordinateUpdates(
            List<ConfigCoordinateInfo> current,
            List<ConfigCoordinateUpdate> requested
    ) {
        Set<String> expectedKeys = new HashSet<>();
        for (ConfigCoordinateInfo coordinate : current) {
            expectedKeys.add(coordinate.key());
        }
        Map<String, BlockPosition> updates = new LinkedHashMap<>();
        for (ConfigCoordinateUpdate update : requested) {
            if (!expectedKeys.contains(update.key())) {
                throw new InvalidConfigCoordinatesException(
                        "Unknown coordinate key: " + update.key()
                );
            }
            BlockPosition previous = updates.putIfAbsent(
                    update.key(),
                    new BlockPosition(update.x(), update.y(), update.z())
            );
            if (previous != null) {
                throw new InvalidConfigCoordinatesException(
                        "Duplicate coordinate key: " + update.key()
                );
            }
        }
        if (!updates.keySet().equals(expectedKeys)) {
            throw new InvalidConfigCoordinatesException("Coordinate keys are missing");
        }
        return Map.copyOf(updates);
    }

    private static StowlingConfig withCoordinates(
            StowlingConfig source,
            Map<String, BlockPosition> updates
    ) {
        List<StorageRoute> routes = new ArrayList<>();
        for (StorageRoute route : source.storageRoutes()) {
            routes.add(new StorageRoute(
                    route.id(),
                    route.itemIds(),
                    route.itemTags(),
                    route.priority(),
                    withCoordinates(route.bulkContainer(), updates),
                    route.packingThresholdMode(),
                    route.packingThreshold(),
                    route.packingStationIds()
            ));
        }
        List<PackingStation> stations = new ArrayList<>();
        for (PackingStation station : source.packingStations()) {
            String key = "packing-station/" + station.id() + "/shulker-box";
            stations.add(new PackingStation(
                    station.id(),
                    station.dimension(),
                    updates.get(key),
                    withCoordinates(station.productContainer(), updates),
                    station.completionTrigger(),
                    station.completionThreshold()
            ));
        }
        StowlingConfig updated = new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                source.coordinatePoints(),
                source.pickupRegions(),
                source.cacheContainers(),
                source.pickupWorkflows(),
                source.automationWorkflows(),
                routes,
                stations,
                source.auditSchedules(),
                withCoordinates(source.overflowContainer(), updates)
        );
        ConfigValidator.requireValid(updated);
        return updated;
    }

    private static ContainerRef withCoordinates(
            ContainerRef container,
            Map<String, BlockPosition> updates
    ) {
        return new ContainerRef(
                container.id(),
                container.dimension(),
                updates.getOrDefault(
                        "container/" + container.id(),
                        container.position()
                ),
                container.type()
        );
    }

    private static boolean isTerminal(PickupStage stage) {
        return stage == PickupStage.COMPLETE || stage == PickupStage.FAILED;
    }

    private static boolean isTerminal(AutomationExecutionStatus status) {
        return status == AutomationExecutionStatus.SUCCEEDED
                || status == AutomationExecutionStatus.FAILED;
    }

    private static String formatPause(AutomationPauseReason reason) {
        return reason.code() + ": " + reason.detail();
    }

    private static InventoryItemInfo toInfo(InventoryItem item) {
        return new InventoryItemInfo(item.itemId(), item.count(), item.kind().name());
    }

    private TaskInfo toInfo(TaskSnapshot task) {
        return toInfo(task, automationProgress(task));
    }

    private TaskInfo toInfo(
            TaskSnapshot task,
            GenericAutomationSnapshot automationProgress
    ) {
        AutomationSnapshot progress = activePickup != null
                && activePickup.taskId.equals(task.id())
                ? activePickup.lastSnapshot
                : null;
        String currentStepId = automationProgress == null
                ? null
                : currentStepId(automationProgress);
        String workflowId = progress != null
                ? progress.workflowId()
                : automationProgress == null
                        ? tasks.runDefinition(task.id()).map(TaskKey::id).orElse(null)
                        : automationProgress.workflowId();
        String currentRegionId = progress == null
                ? null
                : progress.currentRegionId().orElse(null);
        Long round = progress == null ? null : progress.round();
        return new TaskInfo(
                task.id(),
                task.kind().name(),
                task.status().name(),
                task.automationState().name(),
                task.createdAt(),
                task.updatedAt(),
                task.failureReason().orElse(null),
                workflowId,
                currentRegionId,
                round,
                progress != null
                        ? progress.stage().name()
                        : automationProgress == null
                                ? null
                                : task.status() == TaskStatus.CANCELLED
                                        ? TaskStatus.CANCELLED.name()
                                        : automationProgress.status().name(),
                currentStepId,
                automationProgress == null ? null : automationProgress.stepIndex(),
                automationProgress == null ? null : automationProgress.stepCount(),
                automationProgress == null
                        ? null
                        : formatModuleState(automationProgress.moduleState()),
                automationProgress == null
                        ? null
                        : automationProgress.lastConfirmedAction().orElse(null)
        );
    }

    private static GenericAutomationSnapshot initialAutomationProgress(
            UUID taskId,
            AutomationWorkflow workflow
    ) {
        return new GenericAutomationSnapshot(
                taskId,
                workflow.id(),
                AutomationExecutionStatus.READY,
                0,
                workflow.steps().size(),
                Map.of(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private GenericAutomationSnapshot automationProgress(TaskSnapshot task) {
        ActiveAutomation active = activeAutomations.get(task.id());
        if (active != null) {
            return active.lastSnapshot;
        }
        if (task.kind() != TaskKind.AUTOMATION) {
            return null;
        }
        try {
            return new SqliteGenericAutomationCheckpointStore(tasks)
                    .load(task.id())
                    .map(checkpoint -> new GenericAutomationSnapshot(
                            checkpoint.taskId(),
                            checkpoint.workflowId(),
                            checkpoint.status(),
                            checkpoint.stepIndex(),
                            Math.max(
                                    automationStepCount(checkpoint.workflowId()),
                                    checkpoint.stepIndex()
                            ),
                            checkpoint.moduleState(),
                            checkpoint.lastConfirmedAction(),
                            Optional.empty()
                    ))
                    .orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int automationStepCount(String workflowId) {
        return config.automationWorkflows().stream()
                .filter(candidate -> candidate.id().equals(workflowId))
                .findFirst()
                .map(workflow -> workflow.steps().size())
                .orElse(0);
    }

    private String currentStepId(GenericAutomationSnapshot progress) {
        Optional<AutomationWorkflow> workflow = config.automationWorkflows().stream()
                .filter(candidate -> candidate.id().equals(progress.workflowId()))
                .findFirst();
        if (workflow.isEmpty() || progress.stepIndex() >= workflow.orElseThrow().steps().size()) {
            return null;
        }
        return workflow.orElseThrow().steps().get(progress.stepIndex()).id();
    }

    private static String formatModuleState(Map<String, String> state) {
        if (state.isEmpty()) {
            return "{}";
        }
        return state.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static final class ActivePickup {
        private final UUID taskId;
        private final String workflowId;
        private final Optional<String> regionOverride;
        private final PickupAutomationEngine engine;
        private AutomationSnapshot lastSnapshot;

        private ActivePickup(
                UUID taskId,
                String workflowId,
                Optional<String> regionOverride,
                PickupAutomationEngine engine,
                AutomationSnapshot lastSnapshot
        ) {
            this.taskId = taskId;
            this.workflowId = workflowId;
            this.regionOverride = regionOverride;
            this.engine = engine;
            this.lastSnapshot = lastSnapshot;
        }
    }

    private static final class ActiveAutomation {
        private final UUID taskId;
        private final AutomationWorkflow workflow;
        private final GenericAutomationEngine engine;
        private GenericAutomationSnapshot lastSnapshot;

        private ActiveAutomation(
                UUID taskId,
                AutomationWorkflow workflow,
                GenericAutomationEngine engine,
                GenericAutomationSnapshot lastSnapshot
        ) {
            this.taskId = taskId;
            this.workflow = workflow;
            this.engine = engine;
            this.lastSnapshot = lastSnapshot;
        }
    }

}
