package dev.stowling.control;

import dev.stowling.automation.AutomationPauseReason;
import dev.stowling.automation.AutomationCheckpoint;
import dev.stowling.automation.AutomationSnapshot;
import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.PickupAutomationPlan;
import dev.stowling.automation.PickupStage;
import dev.stowling.config.ConfigValidator;
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
import dev.stowling.persistence.InventoryAggregate;
import dev.stowling.persistence.NestedInventoryScope;
import dev.stowling.persistence.PersistedTask;
import dev.stowling.persistence.SqliteAutomationCheckpointStore;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.persistence.TaskRecoveryPlan;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
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

    private StowlingConfig config;
    private ActivePickup activePickup;
    private boolean paused;

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
        this.config = Objects.requireNonNull(config, "config");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.inventorySource = Objects.requireNonNull(inventorySource, "inventorySource");
        this.pickupFactory = Objects.requireNonNull(pickupFactory, "pickupFactory");
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.configReloader = Objects.requireNonNull(configReloader, "configReloader");
        this.configActivated = Objects.requireNonNull(configActivated, "configActivated");
        this.configFileStore = Objects.requireNonNull(configFileStore, "configFileStore");
    }

    @Override
    public synchronized ControlStatus status() {
        String state = paused ? "PAUSED" : activePickup == null
                ? "IDLE"
                : activePickup.lastSnapshot.stage().name();
        return new ControlStatus(paused, state, Instant.now());
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
        if (activePickup != null) {
            replaceDisconnectedPickupOrThrow();
        }
        PickupWorkflow workflow = config.pickupWorkflows().stream()
                .filter(candidate -> candidate.id().equals(request.workflowId()))
                .findFirst()
                .orElseThrow(PickupWorkflowNotFoundException::new);
        validateRegionOverride(workflow, request.regionId());
        Instant now = Instant.now();
        UUID taskId = UUID.randomUUID();
        TaskSnapshot snapshot = new TaskSnapshot(
                taskId,
                TaskKind.PICKUP,
                TaskStatus.PENDING,
                AutomationState.PICKING_UP,
                now,
                now,
                Optional.empty()
        );
        tasks.save(snapshot);
        PickupAutomationEngine engine = pickupFactory.create(
                plan(taskId, workflow, request.regionId())
        );
        activePickup = new ActivePickup(
                taskId,
                workflow.id(),
                request.regionId(),
                engine,
                engine.snapshot()
        );
        clientExecutor.accept(engine::start);
        return toInfo(snapshot);
    }

    private void replaceDisconnectedPickupOrThrow() {
        ActivePickup existing = activePickup;
        if (existing == null || !isDisconnectedPause(existing.lastSnapshot)) {
            throw new ActiveTaskConflictException();
        }
        clientExecutor.accept(existing.engine::cancel);
        TaskSnapshot task = tasks.findById(existing.taskId).orElseThrow();
        tasks.save(new TaskSnapshot(
                task.id(),
                task.kind(),
                TaskStatus.CANCELLED,
                task.automationState(),
                task.createdAt(),
                Instant.now(),
                Optional.of("replaced by a new pickup request after disconnect")
        ));
        activePickup = null;
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
        TaskSnapshot existing = tasks.findById(taskId)
                .orElseThrow(TaskNotFoundException::new);
        if (activePickup != null && activePickup.taskId.equals(taskId)) {
            PickupAutomationEngine engine = activePickup.engine;
            clientExecutor.accept(engine::cancel);
            activePickup = null;
        }
        TaskSnapshot cancelled = new TaskSnapshot(
                existing.id(),
                existing.kind(),
                TaskStatus.CANCELLED,
                existing.automationState(),
                existing.createdAt(),
                Instant.now(),
                Optional.of("cancelled by control request")
        );
        tasks.save(cancelled);
        return toInfo(cancelled);
    }

    @Override
    public synchronized AuditResult requestAudit(AuditRequest request) {
        Objects.requireNonNull(request, "request");
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
                if (error == null) {
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
        for (InventoryAggregate aggregate : inventories.aggregateItems()) {
            result.add(new InventoryInfo(
                    "aggregate",
                    "all-known-containers",
                    "mixed",
                    Instant.now(),
                    "UNKNOWN",
                    List.of(new InventoryItemInfo(
                    aggregate.itemId(),
                    Math.toIntExact(aggregate.totalCount()),
                    aggregate.kind().name()
            ))
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
    public synchronized ControlStatus pause() {
        paused = true;
        if (activePickup != null) {
            PickupAutomationEngine engine = activePickup.engine;
            clientExecutor.accept(engine::pauseForControl);
        }
        return status();
    }

    @Override
    public synchronized ControlStatus resume() {
        paused = false;
        if (activePickup != null) {
            PickupAutomationEngine engine = activePickup.engine;
            clientExecutor.accept(engine::resume);
        }
        return status();
    }

    @Override
    public synchronized ConfigReloadResult reloadConfig() {
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
        Objects.requireNonNull(request, "request");
        if (hasBlockingPickupTask()) {
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

    @Override
    public synchronized ConfigCoordinatesSnapshot configCoordinates() {
        return toCoordinateSnapshot(configFileStore.read());
    }

    @Override
    public synchronized ConfigCoordinatesSnapshot updateConfigCoordinates(
            ConfigCoordinatesUpdateRequest request
    ) {
        Objects.requireNonNull(request, "request");
        if (activePickup != null) {
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

    @Override
    public synchronized PickupConfigSnapshot pickupConfig() {
        return toPickupConfigSnapshot(configFileStore.read());
    }

    @Override
    public synchronized PickupConfigSnapshot updatePickupConfig(
            PickupConfigUpdateRequest request
    ) {
        Objects.requireNonNull(request, "request");
        if (hasBlockingPickupTask()) {
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

    public synchronized void tickOnClientThread() {
        if (paused || activePickup == null) {
            return;
        }
        ActivePickup active = activePickup;
        AutomationSnapshot snapshot;
        try {
            snapshot = active.engine.tick();
        } catch (RuntimeException exception) {
            failTask(active.taskId, "automation failed: " + exception.getClass().getSimpleName());
            activePickup = null;
            return;
        }
        active.lastSnapshot = snapshot;
        persistSnapshot(active, snapshot);
        if (isTerminal(snapshot.stage())) {
            activePickup = null;
        }
    }

    public synchronized void recoverPendingPickups() {
        List<TaskRecoveryPlan> recoveryPlans = tasks.findRecoveryPlans();
        for (TaskRecoveryPlan plan : recoveryPlans) {
            if (plan.kind() != TaskKind.PICKUP) {
                continue;
            }
            if (activePickup != null || plan.checkpoint().isEmpty()) {
                failTask(plan.taskId(), "missing high-level recovery checkpoint");
                continue;
            }
            Optional<RecoveredWorkflow> recovered;
            try {
                recovered = workflowFromCheckpoint(plan.taskId());
            } catch (RuntimeException exception) {
                failTask(plan.taskId(), "invalid high-level recovery checkpoint");
                continue;
            }
            if (recovered.isEmpty()) {
                failTask(plan.taskId(), "recovery pickup workflow is unavailable");
                continue;
            }
            try {
                RecoveredWorkflow selection = recovered.orElseThrow();
                PickupAutomationEngine engine = pickupFactory.create(
                        plan(
                        plan.taskId(),
                        selection.workflow(),
                        selection.regionOverride()
                )
                );
                ActivePickup active = new ActivePickup(
                        plan.taskId(),
                        selection.workflow().id(),
                        selection.regionOverride(),
                        engine,
                        engine.snapshot()
                );
                activePickup = active;
                clientExecutor.accept(() -> startRecoveredPickup(active));
            } catch (RuntimeException exception) {
                activePickup = null;
                failTask(plan.taskId(), "pickup recovery initialization failed");
            }
        }
    }

    private synchronized void startRecoveredPickup(ActivePickup active) {
        if (activePickup != active) {
            return;
        }
        try {
            active.engine.start();
        } catch (RuntimeException exception) {
            failTask(active.taskId, "pickup recovery start failed");
            activePickup = null;
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

    private boolean hasBlockingPickupTask() {
        if (activePickup != null) {
            return true;
        }
        return tasks.findAll().stream().anyMatch(task ->
                task.kind() == TaskKind.PICKUP && switch (task.status()) {
                    case PENDING, RUNNING, WAITING, PAUSED -> true;
                    case SUCCEEDED, FAILED, CANCELLED -> false;
                }
        );
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
                3,
                source.coordinatePoints(),
                synchronizedRegions(request.pickupRegions(), pointsById),
                synchronizedCaches(request.cacheContainers(), pointsById),
                request.pickupWorkflows(),
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
                3,
                coordinates,
                synchronizedRegions(source.pickupRegions(), pointsById),
                synchronizedCaches(source.cacheContainers(), pointsById),
                source.pickupWorkflows(),
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
        return new CuboidPickupShape(
                first.id(),
                second.id(),
                first.position(),
                second.position()
        );
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
            if (!regionIds.isEmpty() || !cacheIds.isEmpty()) {
                throw new InvalidCoordinatePointsException(
                        referencedCoordinateMessage(coordinate.id(), regionIds, cacheIds)
                );
            }
        }
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
            Set<String> cacheIds
    ) {
        List<String> references = new ArrayList<>();
        if (!regionIds.isEmpty()) {
            references.add("pickup regions " + regionIds);
        }
        if (!cacheIds.isEmpty()) {
            references.add("caches " + cacheIds);
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

    private Optional<RecoveredWorkflow> workflowFromCheckpoint(UUID taskId) {
        Optional<AutomationCheckpoint> checkpoint =
                new SqliteAutomationCheckpointStore(tasks).load(taskId);
        if (checkpoint.isEmpty() || checkpoint.orElseThrow().workflowId().isEmpty()) {
            return Optional.empty();
        }
        AutomationCheckpoint value = checkpoint.orElseThrow();
        String workflowId = value.workflowId().orElseThrow();
        Optional<PickupWorkflow> exact = config.pickupWorkflows().stream()
                .filter(workflow -> workflow.id().equals(workflowId))
                .findFirst();
        if (exact.isPresent()) {
            return Optional.of(new RecoveredWorkflow(
                    exact.orElseThrow(),
                    value.regionOverride()
            ));
        }
        return config.pickupWorkflows().stream()
                .filter(workflow -> workflow.regions().stream().anyMatch(
                        binding -> binding.regionId().equals(workflowId)
                ))
                .findFirst()
                .map(workflow -> new RecoveredWorkflow(
                        workflow,
                        Optional.of(workflowId)
                ));
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
                2,
                source.pickupRegions(),
                source.cacheContainers(),
                source.pickupWorkflows(),
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

    private static String formatPause(AutomationPauseReason reason) {
        return reason.code() + ": " + reason.detail();
    }

    private static InventoryItemInfo toInfo(InventoryItem item) {
        return new InventoryItemInfo(item.itemId(), item.count(), item.kind().name());
    }

    private TaskInfo toInfo(TaskSnapshot task) {
        AutomationSnapshot progress = activePickup != null
                && activePickup.taskId.equals(task.id())
                ? activePickup.lastSnapshot
                : null;
        return new TaskInfo(
                task.id(),
                task.kind().name(),
                task.status().name(),
                task.automationState().name(),
                task.createdAt(),
                task.updatedAt(),
                task.failureReason().orElse(null),
                progress == null ? null : progress.workflowId(),
                progress == null ? null : progress.currentRegionId().orElse(null),
                progress == null ? null : progress.round(),
                progress == null ? null : progress.stage().name()
        );
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

    private record RecoveredWorkflow(
            PickupWorkflow workflow,
            Optional<String> regionOverride
    ) {
    }
}
