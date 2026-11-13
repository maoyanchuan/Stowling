package dev.stowling.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.ConfigLoader;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.PickupSite;
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
                PickupSiteNotFoundException.class,
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
                "pickup-site/farm/center",
                "container/shared",
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
        assertEquals(new BlockPosition(108, 65, -10), disk.pickupSites()
                .getFirst().cacheContainer().position());
        assertEquals(
                disk.pickupSites().getFirst().cacheContainer(),
                disk.storageRoutes().getFirst().bulkContainer()
        );
        assertEquals(disk, activated.get());
        assertEquals(101, service.pickupSites().getFirst().center().x());
        assertTrue(service.containers().stream().anyMatch(container ->
                container.id().equals("shared") && container.position().x() == 108));
        assertTrue(saved.revision().matches("[0-9a-f]{64}"));
        assertTrue(java.nio.file.Files.isRegularFile(
                tempDir.resolve("coordinates.json.bak")
        ));
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
                config.schemaVersion(),
                Duration.ofSeconds(5),
                config.pickupSites(),
                config.storageRoutes(),
                config.packingStations(),
                config.auditSchedules(),
                config.overflowContainer()
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
                () -> service.requestPickup(new PickupRequest("farm"))
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
