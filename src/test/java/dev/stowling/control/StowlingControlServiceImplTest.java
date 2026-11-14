package dev.stowling.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.ConfigLoader;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.StowlingConfig;
import dev.stowling.automation.AutomationPauseReason;
import dev.stowling.automation.AutomationSnapshot;
import dev.stowling.automation.PickupStage;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySource;
import dev.stowling.persistence.NestedInventoryScope;
import dev.stowling.persistence.PersistedTask;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.persistence.StowlingDatabase;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StowlingControlServiceImplTest {
    private static final String DIMENSION = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @Test
    void identifiesOnlyDisconnectedPausedPickupsAsReplaceable() {
        AutomationSnapshot disconnected = snapshot(
                PickupStage.PAUSED,
                Optional.of(new AutomationPauseReason("DISCONNECTED", "offline"))
        );
        AutomationSnapshot running = snapshot(PickupStage.COLLECT, Optional.empty());
        AutomationSnapshot controlPaused = snapshot(
                PickupStage.PAUSED,
                Optional.of(new AutomationPauseReason("CONTROL_PAUSED", "paused"))
        );

        assertTrue(StowlingControlServiceImpl.isDisconnectedPause(disconnected));
        assertTrue(!StowlingControlServiceImpl.isDisconnectedPause(running));
        assertTrue(!StowlingControlServiceImpl.isDisconnectedPause(controlPaused));
    }

    @Test
    void exposesConfiguredPickupSitesAsReadOnlyControlDtos() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        StowlingControlServiceImpl service = service(config, source(capture(cache)));

        assertEquals(List.of(new PickupSiteInfo(
                "farm",
                DIMENSION,
                new ContainerPosition(0, 64, 0),
                16,
                0
        )), service.pickupSites());
    }

    @Test
    void reportsSpecificMissingControlResources() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        StowlingControlServiceImpl service = service(config, source(capture(cache)));

        assertThrows(
                PickupWorkflowNotFoundException.class,
                () -> service.requestPickup(new PickupRequest("missing"))
        );
        assertThrows(
                TaskNotFoundException.class,
                () -> service.cancelTask(java.util.UUID.randomUUID())
        );
        assertThrows(
                ContainerNotFoundException.class,
                () -> service.requestAudit(new AuditRequest(List.of("missing")))
        );
    }

    @Test
    void savesEveryCoordinateAndUpdatesReusedContainersInFileAndMemory() {
        ContainerRef shared = container("shared", 8);
        ContainerRef product = container("product", 30);
        ContainerRef overflow = container("overflow", 40);
        PickupSite site = new PickupSite(
                "farm",
                DIMENSION,
                new BlockPosition(1, 64, 2),
                16,
                shared,
                Duration.ZERO
        );
        StorageRoute route = new StorageRoute(
                "stone",
                List.of("minecraft:stone"),
                List.of(),
                1,
                shared,
                PackingTrigger.ON_ITEM_COUNT,
                null,
                List.of()
        );
        PackingStation station = new PackingStation(
                "packer",
                DIMENSION,
                new BlockPosition(20, 64, 0),
                product,
                PackingTrigger.ON_ITEM_COUNT,
                64
        );
        StowlingConfig config = new StowlingConfig(
                1,
                Duration.ZERO,
                List.of(site),
                List.of(route),
                List.of(station),
                List.of(),
                overflow
        );
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve("coordinates.json");
        loader.write(configPath, config);
        AtomicReference<StowlingConfig> activated = new AtomicReference<>();
        StowlingControlServiceImpl service = service(
                config,
                source(capture(shared)),
                new ConfigFileStore(configPath, loader),
                activated::set
        );
        ConfigCoordinatesSnapshot before = service.configCoordinates();
        Set<String> keys = before.coordinates().stream()
                .map(ConfigCoordinateInfo::key)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "packing-station/packer/shulker-box",
                "container/product",
                "container/overflow"
        ), keys);
        List<ConfigCoordinateUpdate> updates = before.coordinates().stream()
                .map(StowlingControlServiceImplTest::movedUpdate)
                .toList();

        ConfigCoordinatesSnapshot saved = service.updateConfigCoordinates(
                new ConfigCoordinatesUpdateRequest(before.revision(), updates)
        );

        StowlingConfig disk = loader.load(configPath);
        assertEquals(new BlockPosition(8, 64, 0), disk.pickupSites()
                .getFirst().cacheContainer().position());
        assertEquals(
                disk.pickupSites().getFirst().cacheContainer(),
                disk.storageRoutes().getFirst().bulkContainer()
        );
        assertEquals(disk, activated.get());
        assertEquals(1, service.pickupSites().getFirst().center().x());
        assertTrue(service.containers().stream().anyMatch(container ->
                container.id().equals("shared") && container.position().x() == 8));
        assertTrue(saved.revision().matches("[0-9a-f]{64}"));
        assertTrue(java.nio.file.Files.isRegularFile(
                tempDir.resolve("coordinates.json.bak")
        ));
    }

    @Test
    void savesCoordinatePointsAndPropagatesEveryReference() {
        StowlingConfig config = referencedConfig();
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve("coordinate-points.json");
        loader.write(configPath, config);
        AtomicReference<StowlingConfig> activated = new AtomicReference<>();
        StowlingControlServiceImpl service = service(
                config,
                source(capture(container("cache", 20))),
                new ConfigFileStore(configPath, loader),
                activated::set
        );
        ConfigCoordinatePointsSnapshot before = service.coordinatePoints();
        List<ConfigCoordinatePointInfo> movedPoints = List.of(
                movedPoint(before, "standby", 5, 65, 1),
                movedPoint(before, "first-corner", 2, 64, 0),
                movedPoint(before, "second-corner", 11, 70, 10),
                movedPoint(before, "cache-position", 21, 64, 1)
        );
        ConfigCoordinatePointsUpdateRequest updateRequest =
                new ConfigCoordinatePointsUpdateRequest(
                before.revision(),
                movedPoints
        );

        ConfigCoordinatePointsSnapshot saved = service.updateCoordinatePoints(
                updateRequest
        );

        StowlingConfig disk = loader.load(configPath);
        PickupRegion region = disk.pickupRegions().getFirst();
        CuboidPickupShape shape = (CuboidPickupShape) region.shape();
        assertEquals(new BlockPosition(5, 65, 1), region.standbyPosition());
        assertEquals(new BlockPosition(2, 64, 0), shape.firstCorner());
        assertEquals(new BlockPosition(11, 70, 10), shape.secondCorner());
        assertEquals(new BlockPosition(21, 64, 1), disk.cacheContainers()
                .getFirst()
                .position());
        assertEquals(disk, activated.get());
        assertEquals(saved, service.coordinatePoints());
        assertTrue(java.nio.file.Files.isRegularFile(
                tempDir.resolve("coordinate-points.json.bak")
        ));

        List<ConfigCoordinatePointInfo> renamed = renamedStandby(saved);
        ConfigCoordinatePointsUpdateRequest renameRequest =
                new ConfigCoordinatePointsUpdateRequest(saved.revision(), renamed);
        ConfigCoordinatePointsSnapshot renameSaved =
                service.updateCoordinatePoints(renameRequest);

        assertEquals(
                "Renamed standby",
                loader.load(configPath).coordinatePoints().getFirst().name()
        );
        ConfigCoordinatePointsUpdateRequest staleRequest =
                new ConfigCoordinatePointsUpdateRequest(
                before.revision(),
                before.coordinatePoints()
        );
        assertThrows(
                ConfigRevisionConflictException.class,
                () -> service.updateCoordinatePoints(staleRequest)
        );
        assertTrue(renameSaved.revision().matches("[0-9a-f]{64}"));
    }

    @Test
    void savesSphereCenterAndSharedStandbyCoordinate() {
        ContainerRef cache = container("cache", 8);
        StowlingConfig config = config(cache, container("overflow", 40));
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve("sphere-coordinate-points.json");
        loader.write(configPath, config);
        StowlingControlServiceImpl service = service(
                config,
                source(capture(cache)),
                new ConfigFileStore(configPath, loader),
                ignored -> {
                }
        );
        ConfigCoordinatePointsSnapshot before = service.coordinatePoints();
        List<ConfigCoordinatePointInfo> moved = new ArrayList<>();
        for (ConfigCoordinatePointInfo point : before.coordinatePoints()) {
            if (point.id().equals("farm-standby")) {
                moved.add(movedPoint(before, point.id(), 6, 64, 0));
            } else {
                moved.add(point);
            }
        }
        ConfigCoordinatePointsUpdateRequest request =
                new ConfigCoordinatePointsUpdateRequest(before.revision(), moved);

        service.updateCoordinatePoints(request);

        PickupRegion region = loader.load(configPath).pickupRegions().getFirst();
        SpherePickupShape shape = (SpherePickupShape) region.shape();
        assertEquals(new BlockPosition(6, 64, 0), region.standbyPosition());
        assertEquals(new BlockPosition(6, 64, 0), shape.center());
    }

    @Test
    void rejectsDeletingReferencedCoordinateAndCrossDimensionChange() {
        StowlingConfig config = referencedConfig();
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve("coordinate-references.json");
        loader.write(configPath, config);
        StowlingControlServiceImpl service = service(
                config,
                source(capture(container("cache", 20))),
                new ConfigFileStore(configPath, loader),
                ignored -> {
                }
        );
        ConfigCoordinatePointsSnapshot before = service.coordinatePoints();
        List<ConfigCoordinatePointInfo> withoutStandby = before.coordinatePoints()
                .stream()
                .filter(point -> !point.id().equals("standby"))
                .toList();
        List<ConfigCoordinatePointInfo> withoutCache = before.coordinatePoints()
                .stream()
                .filter(point -> !point.id().equals("cache-position"))
                .toList();
        ConfigCoordinatePointsUpdateRequest deletionRequest =
                new ConfigCoordinatePointsUpdateRequest(
                before.revision(),
                withoutStandby
        );
        ConfigCoordinatePointsUpdateRequest cacheDeletionRequest =
                new ConfigCoordinatePointsUpdateRequest(
                before.revision(),
                withoutCache
        );

        InvalidCoordinatePointsException deletion = assertThrows(
                InvalidCoordinatePointsException.class,
                () -> service.updateCoordinatePoints(deletionRequest)
        );
        InvalidCoordinatePointsException cacheDeletion = assertThrows(
                InvalidCoordinatePointsException.class,
                () -> service.updateCoordinatePoints(cacheDeletionRequest)
        );
        List<ConfigCoordinatePointInfo> crossDimension = crossDimension(before);
        ConfigCoordinatePointsUpdateRequest dimensionRequest =
                new ConfigCoordinatePointsUpdateRequest(
                before.revision(),
                crossDimension
        );
        InvalidCoordinatePointsException dimension = assertThrows(
                InvalidCoordinatePointsException.class,
                () -> service.updateCoordinatePoints(dimensionRequest)
        );

        assertTrue(deletion.getMessage().contains("pickup regions [farm]"));
        assertTrue(cacheDeletion.getMessage().contains("caches [cache]"));
        assertTrue(dimension.getMessage().contains("dimension"));
    }

    @Test
    void rejectsExternallyModifiedConfigRevision() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve("revision.json");
        loader.write(configPath, config);
        StowlingControlServiceImpl service = service(
                config,
                source(capture(cache)),
                new ConfigFileStore(configPath, loader),
                ignored -> {
                }
        );
        ConfigCoordinatesSnapshot snapshot = service.configCoordinates();
        loader.write(configPath, new StowlingConfig(
                2,
                config.pickupRegions(),
                config.cacheContainers(),
                config.pickupWorkflows(),
                config.storageRoutes(),
                config.packingStations(),
                config.auditSchedules(),
                container("overflow", 11)
        ));
        List<ConfigCoordinateUpdate> unchanged = snapshot.coordinates().stream()
                .map(StowlingControlServiceImplTest::unchangedUpdate)
                .toList();
        ConfigCoordinatesUpdateRequest request = new ConfigCoordinatesUpdateRequest(
                snapshot.revision(),
                unchanged
        );

        assertThrows(
                ConfigRevisionConflictException.class,
                () -> service.updateConfigCoordinates(request)
        );
    }

    @Test
    void savesPickupResourcesWithoutLosingWarehouseConfiguration() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        ConfigLoader loader = new ConfigLoader();
        Path configPath = tempDir.resolve("pickup-config.json");
        loader.write(configPath, config);
        AtomicReference<StowlingConfig> activated = new AtomicReference<>();
        StowlingControlServiceImpl service = service(
                config,
                source(capture(cache)),
                new ConfigFileStore(configPath, loader),
                activated::set
        );
        List<NamedCoordinate> coordinatePoints = loader.load(configPath)
                .coordinatePoints();
        PickupConfigSnapshot before = service.pickupConfig();
        PickupRegion originalRegion = before.pickupRegions().getFirst();
        PickupWorkflow originalWorkflow = before.pickupWorkflows().getFirst();
        PickupRegion renamedRegion = new PickupRegion(
                originalRegion.id(),
                "Renamed farm",
                originalRegion.dimension(),
                originalRegion.standbyCoordinateId(),
                originalRegion.standbyPosition(),
                originalRegion.shape()
        );
        PickupWorkflow renamedWorkflow = new PickupWorkflow(
                originalWorkflow.id(),
                "Renamed workflow",
                originalWorkflow.dimension(),
                originalWorkflow.regions(),
                originalWorkflow.cacheIds(),
                originalWorkflow.cachePolicy(),
                originalWorkflow.classificationTiming(),
                originalWorkflow.completionMode(),
                originalWorkflow.completionIdleDelay()
        );

        PickupConfigSnapshot saved = service.updatePickupConfig(
                new PickupConfigUpdateRequest(
                before.revision(),
                List.of(renamedRegion),
                before.cacheContainers(),
                List.of(renamedWorkflow)
        )
        );

        StowlingConfig disk = loader.load(configPath);
        assertEquals("Renamed farm", disk.pickupRegions().getFirst().name());
        assertEquals("Renamed workflow", disk.pickupWorkflows().getFirst().name());
        assertEquals(config.overflowContainer(), disk.overflowContainer());
        assertEquals(config.storageRoutes(), disk.storageRoutes());
        assertEquals(coordinatePoints, disk.coordinatePoints());
        assertEquals(disk, activated.get());
        assertEquals(saved, service.pickupConfig());
        assertTrue(java.nio.file.Files.isRegularFile(
                tempDir.resolve("pickup-config.json.bak")
        ));
    }

    @Test
    void rejectsPickupResourceDeletionWhenWorkflowStillReferencesIt() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        StowlingControlServiceImpl service = service(config, source(capture(cache)));
        PickupConfigSnapshot before = service.pickupConfig();

        InvalidPickupConfigException exception = assertThrows(
                InvalidPickupConfigException.class,
                () -> service.updatePickupConfig(new PickupConfigUpdateRequest(
                before.revision(),
                List.of(),
                before.cacheContainers(),
                before.pickupWorkflows()
        ))
        );

        assertTrue(exception.getMessage().contains("farm-workflow"));
        assertTrue(exception.getMessage().contains("farm"));
    }

    @Test
    void rejectsPickupConfigurationWhilePausedTaskExists() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve("paused.db"));
        SqliteTaskRepository tasks = new SqliteTaskRepository(database);
        Instant now = Instant.now();
        tasks.save(new TaskSnapshot(
                UUID.randomUUID(),
                TaskKind.PICKUP,
                TaskStatus.PAUSED,
                AutomationState.FAILED,
                now,
                now,
                Optional.empty()
        ));
        ConfigFileStore store = configStore(config, "paused.json");
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config,
                tasks,
                new SqliteInventoryRepository(database),
                source(capture(cache)),
                ignored -> {
                    throw new AssertionError("pickup runtime is not used");
                },
                Runnable::run,
                () -> config,
                ignored -> {
                },
                store
        );
        PickupConfigSnapshot before = service.pickupConfig();
        ConfigCoordinatePointsSnapshot points = service.coordinatePoints();
        ConfigCoordinatePointsUpdateRequest pointRequest =
                new ConfigCoordinatePointsUpdateRequest(
                points.revision(),
                points.coordinatePoints()
        );

        assertThrows(
                ActiveTaskConflictException.class,
                () -> service.updatePickupConfig(new PickupConfigUpdateRequest(
                before.revision(),
                before.pickupRegions(),
                before.cacheContainers(),
                before.pickupWorkflows()
        ))
        );
        assertThrows(
                ActiveTaskConflictException.class,
                () -> service.updateCoordinatePoints(pointRequest)
        );
    }

    @Test
    void auditPersistsDetailedNestedCapture() {
        ContainerRef cache = container("cache", 0);
        ContainerRef overflow = container("overflow", 10);
        StowlingConfig config = config(cache, overflow);
        InventoryCapture capture = capture(cache);
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve("audit.db"));
        SqliteInventoryRepository inventories = new SqliteInventoryRepository(database);
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config,
                new SqliteTaskRepository(database),
                inventories,
                source(capture),
                ignored -> {
                    throw new AssertionError("pickup runtime is not used by an audit");
                },
                Runnable::run,
                () -> config,
                Objects::requireNonNull,
                configStore(config, "audit.json")
        );

        service.requestAudit(new AuditRequest(List.of(cache.id())));

        StoredInventorySnapshot stored = inventories.findSnapshot(cache.id()).orElseThrow();
        assertEquals(capture.snapshot(), stored.snapshot());
        assertEquals(List.of(new NestedInventoryScope(
                "cache/slot/2",
                Optional.of("cache"),
                List.of(new InventoryItem("minecraft:diamond", 6, InventoryItemKind.BOXED))
        )), stored.nestedScopes());
    }

    @Test
    void legacyPausedRecoveryFailsWithoutBlockingANewPickup() {
        assertInvalidRecoveryDoesNotBlock("farm|PAUSED", "legacy-paused");
    }

    @Test
    void damagedRecoveryFailsWithoutBlockingANewPickup() {
        assertInvalidRecoveryDoesNotBlock("{damaged-json", "damaged");
    }

    private void assertInvalidRecoveryDoesNotBlock(String checkpoint, String suffix) {
        ContainerRef cache = container("cache", 0);
        StowlingConfig config = config(cache, container("overflow", 10));
        StowlingDatabase database = new StowlingDatabase(
                tempDir.resolve("recovery-" + suffix + ".db")
        );
        SqliteTaskRepository tasks = new SqliteTaskRepository(database);
        UUID oldTaskId = UUID.randomUUID();
        Instant now = Instant.now();
        TaskSnapshot pausedTask = new TaskSnapshot(
                oldTaskId,
                TaskKind.PICKUP,
                TaskStatus.PAUSED,
                AutomationState.FAILED,
                now,
                now,
                Optional.empty()
        );
        tasks.save(new PersistedTask(
                pausedTask,
                Optional.of(checkpoint),
                0
        ));
        AtomicInteger factoryCalls = new AtomicInteger();
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config,
                tasks,
                new SqliteInventoryRepository(database),
                source(capture(cache)),
                ignored -> {
                    factoryCalls.incrementAndGet();
                    throw new AssertionError("pickup factory reached");
                },
                Runnable::run,
                () -> config,
                ignored -> {
                },
                configStore(config, "recovery-" + suffix + ".json")
        );

        service.recoverPendingPickups();

        assertEquals(TaskStatus.FAILED, tasks.findById(oldTaskId).orElseThrow().status());
        assertEquals(0, factoryCalls.get());
        assertThrows(
                AssertionError.class,
                () -> service.requestPickup(new PickupRequest("farm-workflow"))
        );
        assertEquals(1, factoryCalls.get());
    }

    private StowlingControlServiceImpl service(
            StowlingConfig config,
            InventorySource inventorySource
    ) {
        return service(
                config,
                inventorySource,
                configStore(config, "control.json"),
                Objects::requireNonNull
        );
    }

    private StowlingControlServiceImpl service(
            StowlingConfig config,
            InventorySource inventorySource,
            ConfigFileStore configFileStore,
            java.util.function.Consumer<StowlingConfig> configActivated
    ) {
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve("control.db"));
        return new StowlingControlServiceImpl(
                config,
                new SqliteTaskRepository(database),
                new SqliteInventoryRepository(database),
                inventorySource,
                ignored -> {
                    throw new AssertionError("pickup runtime is not used by this test");
                },
                Runnable::run,
                () -> config,
                configActivated,
                configFileStore
        );
    }

    private ConfigFileStore configStore(StowlingConfig config, String fileName) {
        ConfigLoader loader = new ConfigLoader();
        Path path = tempDir.resolve(fileName);
        loader.write(path, config);
        return new ConfigFileStore(path, loader);
    }

    private static AutomationSnapshot snapshot(
            PickupStage stage,
            Optional<AutomationPauseReason> pauseReason
    ) {
        return new AutomationSnapshot(
                UUID.randomUUID(),
                stage,
                pauseReason,
                Optional.empty(),
                Optional.empty(),
                0,
                0
        );
    }

    private static InventorySource source(InventoryCapture capture) {
        return new InventorySource() {
            @Override
            public String sourceId() {
                return "test";
            }

            @Override
            public CompletableFuture<InventoryCapture> capture(ContainerRef container) {
                return CompletableFuture.completedFuture(capture);
            }
        };
    }

    private static InventoryCapture capture(ContainerRef container) {
        return InventoryCapture.fromSlots(
                "test",
                container.id(),
                container.dimension(),
                Instant.EPOCH,
                InventoryFreshness.FRESH,
                List.of(new InventoryCapture.SlotCapture(
                        2,
                        "minecraft:shulker_box",
                        1,
                        InventoryItemKind.BOXED,
                        List.of(new InventoryCapture.ItemCount("minecraft:diamond", 6))
                ))
        );
    }

    private static StowlingConfig config(ContainerRef cache, ContainerRef overflow) {
        PickupSite site = new PickupSite(
                "farm",
                DIMENSION,
                new BlockPosition(0, 64, 0),
                16,
                cache,
                Duration.ZERO
        );
        return new StowlingConfig(
                1,
                Duration.ZERO,
                List.of(site),
                List.of(),
                List.of(),
                List.of(),
                overflow
        );
    }

    private static StowlingConfig referencedConfig() {
        NamedCoordinate standby = new NamedCoordinate(
                "standby",
                "Standby",
                DIMENSION,
                new BlockPosition(0, 64, 0)
        );
        NamedCoordinate first = new NamedCoordinate(
                "first-corner",
                "First corner",
                DIMENSION,
                new BlockPosition(1, 64, 0)
        );
        NamedCoordinate second = new NamedCoordinate(
                "second-corner",
                "Second corner",
                DIMENSION,
                new BlockPosition(10, 70, 10)
        );
        NamedCoordinate cachePoint = new NamedCoordinate(
                "cache-position",
                "Cache position",
                DIMENSION,
                new BlockPosition(20, 64, 0)
        );
        CuboidPickupShape shape = new CuboidPickupShape(
                first.id(),
                second.id(),
                first.position(),
                second.position()
        );
        PickupRegion region = new PickupRegion(
                "farm",
                "Farm",
                DIMENSION,
                standby.id(),
                standby.position(),
                shape
        );
        NamedCache cache = new NamedCache(
                "cache",
                "Cache",
                cachePoint.id(),
                cachePoint.dimension(),
                cachePoint.position(),
                ContainerType.CHEST
        );
        PickupWorkflow workflow = new PickupWorkflow(
                "farm-workflow",
                "Farm workflow",
                DIMENSION,
                List.of(new PickupRegionBinding("farm", 100, Duration.ZERO)),
                List.of(cache.id()),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        return new StowlingConfig(
                3,
                List.of(standby, first, second, cachePoint),
                List.of(region),
                List.of(cache),
                List.of(workflow),
                List.of(),
                List.of(),
                List.of(),
                container("overflow", 40)
        );
    }

    private static List<ConfigCoordinatePointInfo> renamedStandby(
            ConfigCoordinatePointsSnapshot snapshot
    ) {
        List<ConfigCoordinatePointInfo> result = new ArrayList<>();
        for (ConfigCoordinatePointInfo point : snapshot.coordinatePoints()) {
            if (point.id().equals("standby")) {
                result.add(new ConfigCoordinatePointInfo(
                        point.id(),
                        "Renamed standby",
                        point.dimension(),
                        point.position()
                ));
            } else {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private static List<ConfigCoordinatePointInfo> crossDimension(
            ConfigCoordinatePointsSnapshot snapshot
    ) {
        List<ConfigCoordinatePointInfo> result = new ArrayList<>();
        for (ConfigCoordinatePointInfo point : snapshot.coordinatePoints()) {
            if (point.id().equals("standby")) {
                result.add(new ConfigCoordinatePointInfo(
                        point.id(),
                        point.name(),
                        "minecraft:the_nether",
                        point.position()
                ));
            } else {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private static ConfigCoordinatePointInfo movedPoint(
            ConfigCoordinatePointsSnapshot snapshot,
            String id,
            int x,
            int y,
            int z
    ) {
        ConfigCoordinatePointInfo point = snapshot.coordinatePoints().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        return new ConfigCoordinatePointInfo(
                point.id(),
                point.name(),
                point.dimension(),
                new ContainerPosition(x, y, z)
        );
    }

    private static ContainerRef container(String id, int x) {
        return new ContainerRef(
                id,
                DIMENSION,
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    private static ConfigCoordinateUpdate movedUpdate(ConfigCoordinateInfo coordinate) {
        return new ConfigCoordinateUpdate(
                coordinate.key(),
                coordinate.position().x() + 100,
                coordinate.position().y() + 1,
                coordinate.position().z() - 10
        );
    }

    private static ConfigCoordinateUpdate unchangedUpdate(ConfigCoordinateInfo coordinate) {
        return new ConfigCoordinateUpdate(
                coordinate.key(),
                coordinate.position().x(),
                coordinate.position().y(),
                coordinate.position().z()
        );
    }
}
