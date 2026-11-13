package dev.stowling.control;

import dev.stowling.automation.AutomationPauseReason;
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
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupSite;
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
        return tasks.findAll().stream().map(StowlingControlServiceImpl::toInfo).toList();
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
    public synchronized TaskInfo requestPickup(PickupRequest request) {
        Objects.requireNonNull(request, "request");
        if (activePickup != null) {
            throw new IllegalStateException("another pickup task is active");
        }
        PickupSite site = config.pickupSites().stream()
                .filter(candidate -> candidate.id().equals(request.siteId()))
                .findFirst()
                .orElseThrow(PickupSiteNotFoundException::new);
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
        PickupAutomationEngine engine = pickupFactory.create(plan(taskId, site));
        activePickup = new ActivePickup(taskId, site.id(), engine, engine.snapshot());
        clientExecutor.accept(engine::start);
        return toInfo(snapshot);
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
            Optional<PickupSite> site = siteFromCheckpoint(plan.checkpoint().orElseThrow());
            if (site.isEmpty()) {
                failTask(plan.taskId(), "recovery pickup site is unavailable");
                continue;
            }
            PickupAutomationEngine engine = pickupFactory.create(plan(plan.taskId(), site.get()));
            activePickup = new ActivePickup(
                    plan.taskId(),
                    site.get().id(),
                    engine,
                    engine.snapshot()
            );
            clientExecutor.accept(engine::start);
        }
    }

    private PickupAutomationPlan plan(UUID taskId, PickupSite site) {
        return new PickupAutomationPlan(
                taskId,
                site,
                config.storageRoutes(),
                config.overflowContainer(),
                INVENTORY_RESERVE_SLOTS,
                MAX_CONTAINER_RETRIES,
                CONTAINER_ACK_TIMEOUT,
                NAVIGATION_TIMEOUT
        );
    }

    private void persistSnapshot(ActivePickup active, AutomationSnapshot snapshot) {
        TaskSnapshot existing = tasks.findById(active.taskId).orElseThrow();
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
                : Optional.of(active.siteId + "|" + snapshot.stage().name());
        tasks.save(new PersistedTask(updated, checkpoint, 0));
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

    private Optional<PickupSite> siteFromCheckpoint(String checkpoint) {
        int separator = checkpoint.indexOf('|');
        String siteId = separator < 0 ? "" : checkpoint.substring(0, separator);
        return config.pickupSites().stream()
                .filter(site -> site.id().equals(siteId))
                .findFirst();
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
        for (PickupSite site : config.pickupSites()) {
            result.addAll(site.cacheContainers());
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

    private static List<ConfigCoordinateInfo> coordinateInfos(StowlingConfig config) {
        LinkedHashMap<String, ConfigCoordinateInfo> result = new LinkedHashMap<>();
        for (PickupSite site : config.pickupSites()) {
            String key = "pickup-site/" + site.id() + "/center";
            result.put(key, coordinateInfo(
                    key,
                    "PICKUP_SITE_CENTER",
                    site.id(),
                    "Pickup site " + site.id(),
                    site.dimension(),
                    site.center()
            ));
            for (ContainerRef container : site.cacheContainers()) {
                registerContainer(result, container, "Cache container " + container.id());
            }
        }
        for (StorageRoute route : config.storageRoutes()) {
            ContainerRef container = route.bulkContainer();
            registerContainer(result, container, "Storage container " + container.id());
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
            registerContainer(result, container, "Product container " + container.id());
        }
        ContainerRef overflow = config.overflowContainer();
        registerContainer(result, overflow, "Overflow container " + overflow.id());
        return List.copyOf(result.values());
    }

    private static void registerContainer(
            Map<String, ConfigCoordinateInfo> coordinates,
            ContainerRef container,
            String label
    ) {
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
        List<PickupSite> pickupSites = new ArrayList<>();
        for (PickupSite site : source.pickupSites()) {
            List<ContainerRef> caches = new ArrayList<>();
            for (ContainerRef container : site.cacheContainers()) {
                caches.add(withCoordinates(container, updates));
            }
            pickupSites.add(new PickupSite(
                    site.id(),
                    site.dimension(),
                    updates.get("pickup-site/" + site.id() + "/center"),
                    site.radius(),
                    caches,
                    site.idleDelay()
            ));
        }
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
                source.schemaVersion(),
                source.defaultIdleDelay(),
                pickupSites,
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
                updates.get("container/" + container.id()),
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

    private static TaskInfo toInfo(TaskSnapshot task) {
        return new TaskInfo(
                task.id(),
                task.kind().name(),
                task.status().name(),
                task.automationState().name(),
                task.createdAt(),
                task.updatedAt(),
                task.failureReason().orElse(null)
        );
    }

    private static final class ActivePickup {
        private final UUID taskId;
        private final String siteId;
        private final PickupAutomationEngine engine;
        private AutomationSnapshot lastSnapshot;

        private ActivePickup(
                UUID taskId,
                String siteId,
                PickupAutomationEngine engine,
                AutomationSnapshot lastSnapshot
        ) {
            this.taskId = taskId;
            this.siteId = siteId;
            this.engine = engine;
            this.lastSnapshot = lastSnapshot;
        }
    }
}
