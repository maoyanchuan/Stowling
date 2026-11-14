package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationOperationStatus;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.MaterialAcquisitionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.automation.PlayerInventoryAdapter;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.SystemAutomationClock;
import dev.stowling.automation.module.DefaultAutomationModuleRegistryFactory;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import dev.stowling.persistence.SqliteGenericAutomationCheckpointStore;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StowlingDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientAutomationAdaptersTest {
    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final ContainerRef OVERFLOW = container("overflow", 0, 64, 0);

    @TempDir
    Path tempDir;

    @Test
    void coordinateResolverReadsTheCurrentConfig() {
        NamedCoordinate coordinate = new NamedCoordinate(
                "mine",
                "Mine",
                "minecraft:overworld",
                new BlockPosition(12, 64, -4)
        );
        StowlingConfig config = config(List.of(coordinate));
        ConfigCoordinateResolver resolver = new ConfigCoordinateResolver(
                () -> config
        );

        assertEquals(
                Optional.of(new BlockPosition(12, 64, -4)),
                resolver.resolve("mine")
        );
        assertTrue(resolver.resolve("missing").isEmpty());
    }

    @Test
    void runtimeFactoryExposesAllNineModules() {
        StowlingDatabase database = database("factory.db");
        SqliteTaskRepository tasks = new SqliteTaskRepository(database);
        TestContainerInteraction containers = new TestContainerInteraction();
        TestScheduler scheduler = new TestScheduler(true);
        ClientThreadItemTransferPort transfers =
                new ClientThreadItemTransferPort(containers, scheduler);
        DefaultAutomationModuleRegistryFactory moduleFactory = moduleFactory(
                transfers
        );
        SqliteGenericAutomationCheckpointStore checkpoints =
                new SqliteGenericAutomationCheckpointStore(tasks);
        StowlingAutomationRuntimeFactory factory =
                new StowlingAutomationRuntimeFactory(moduleFactory, checkpoints);

        List<AutomationModuleDescriptor> descriptors = factory.modules();
        Set<AutomationStepType> types = new HashSet<>();
        descriptors.forEach(descriptor -> types.add(descriptor.type()));

        assertEquals(9, descriptors.size());
        assertEquals(Set.of(AutomationStepType.values()), types);
    }

    @Test
    void materialUsesLatestFreshContainerAndExcludesStaleUnknownEntries() {
        SqliteInventoryRepository inventories = inventories("inventory.db");
        ContainerRef fresh = container("fresh", 1, 64, 0);
        ContainerRef stale = container("stale", 2, 64, 0);
        ContainerRef unknown = container("unknown", 3, 64, 0);
        inventories.replaceSnapshot(
                fresh,
                snapshot(fresh, InventoryFreshness.FRESH, "minecraft:iron_ingot", 5),
                START
        );
        inventories.replaceSnapshot(
                stale,
                snapshot(stale, InventoryFreshness.STALE, "minecraft:iron_ingot", 7),
                START.plusSeconds(1)
        );
        inventories.replaceSnapshot(
                unknown,
                snapshot(unknown, InventoryFreshness.UNKNOWN, "minecraft:iron_ingot", 9),
                START.plusSeconds(2)
        );
        ContainerRef oldMaterial = container("material-old", 4, 64, 0);
        ContainerRef latestMaterial = container("material-new", 5, 64, 0);
        ContainerRef staleMaterial = container("material-stale", 6, 64, 0);
        inventories.replaceSnapshot(
                oldMaterial,
                snapshot(
                oldMaterial,
                InventoryFreshness.FRESH,
                "minecraft:cobblestone",
                8
        ),
                START.plusSeconds(3)
        );
        inventories.replaceSnapshot(
                latestMaterial,
                snapshot(
                latestMaterial,
                InventoryFreshness.FRESH,
                "minecraft:cobblestone",
                4
        ),
                START.plusSeconds(4)
        );
        inventories.replaceSnapshot(
                staleMaterial,
                snapshot(
                staleMaterial,
                InventoryFreshness.STALE,
                "minecraft:cobblestone",
                100
        ),
                START.plusSeconds(5)
        );

        RecordingTransfer transfer = new RecordingTransfer();
        SqliteMaterialAcquisitionPort materials =
                new SqliteMaterialAcquisitionPort(inventories, transfer);
        AutomationOperationResult started = materials.start(
                "task:acquire",
                new MaterialAcquisitionPort.Request("minecraft:cobblestone", 3)
        );

        assertEquals(AutomationOperationStatus.RUNNING, started.status());
        assertEquals("material-new", transfer.started.getFirst().source());
        assertEquals(ItemTransferPort.Mode.TAKE, transfer.started.getFirst().mode());
    }

    @Test
    void inventoryQueryUsesPlayerInventoryAndRejectsOffThreadCalls() {
        PlayerInventorySnapshot playerSnapshot = new PlayerInventorySnapshot(
                36,
                List.of(new PlayerInventorySlot(4, "minecraft:stone", 12))
        );
        PlayerInventoryAdapter player = () -> playerSnapshot;
        ClientThreadInventoryQueryPort inventory =
                new ClientThreadInventoryQueryPort(player, new TestScheduler(true));

        assertEquals(playerSnapshot, inventory.snapshot());

        ClientThreadInventoryQueryPort offThread =
                new ClientThreadInventoryQueryPort(player, new TestScheduler(false));
        assertThrows(IllegalStateException.class, offThread::snapshot);
    }

    @Test
    void missingAttemptsPauseWithoutRestartingOrClaimingSuccess() {
        TestScheduler scheduler = new TestScheduler(true);
        TestContainerInteraction containers = new TestContainerInteraction();
        ClientThreadItemTransferPort transfers =
                new ClientThreadItemTransferPort(containers, scheduler);
        RecordingTransfer recordingTransfer = new RecordingTransfer();
        SqliteMaterialAcquisitionPort materials = new SqliteMaterialAcquisitionPort(
                inventories("missing.db"),
                recordingTransfer
        );
        ClientPickupWorkflowPort pickups = new ClientPickupWorkflowPort(
                () -> config(List.of()),
                plan -> {
                    throw new AssertionError("reconcile must not create an engine");
                },
                scheduler
        );

        AutomationOperationResult transferResult =
                transfers.reconcile("missing-transfer");
        AutomationOperationResult materialResult =
                materials.reconcile("missing-material");
        AutomationOperationResult pickupResult =
                pickups.reconcile("missing-pickup");

        assertEquals(AutomationOperationStatus.PAUSED, transferResult.status());
        assertEquals(AutomationOperationStatus.PAUSED, materialResult.status());
        assertEquals(AutomationOperationStatus.PAUSED, pickupResult.status());
        assertFalse(transferResult.lastConfirmedAction().isPresent());
        assertFalse(materialResult.lastConfirmedAction().isPresent());
        assertFalse(pickupResult.lastConfirmedAction().isPresent());
        assertTrue(containers.starts.isEmpty());
        assertTrue(recordingTransfer.started.isEmpty());
    }

    private SqliteInventoryRepository inventories(String fileName) {
        return new SqliteInventoryRepository(database(fileName));
    }

    private StowlingDatabase database(String fileName) {
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve(fileName));
        database.ensureMigrated();
        return database;
    }

    private DefaultAutomationModuleRegistryFactory moduleFactory(
            ItemTransferPort transfers
    ) {
        NavigationPort navigation = new NoopNavigation();
        InventoryQueryPort inventory =
                () -> new PlayerInventorySnapshot(36, List.of());
        return new DefaultAutomationModuleRegistryFactory(
                coordinateId -> Optional.of(new BlockPosition(0, 64, 0)),
                navigation,
                new SystemAutomationClock(),
                new StubPickupPort(),
                transfers,
                inventory,
                new StubMaterialPort(),
                new UnsupportedClientCraftingPort()
        );
    }

    private static StowlingConfig config(List<NamedCoordinate> coordinates) {
        return new StowlingConfig(
                4,
                coordinates,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                OVERFLOW
        );
    }

    private static ContainerRef container(
            String id,
            int x,
            int y,
            int z
    ) {
        return new ContainerRef(
                id,
                "minecraft:overworld",
                new BlockPosition(x, y, z),
                ContainerType.CHEST
        );
    }

    private static InventorySnapshot snapshot(
            ContainerRef container,
            InventoryFreshness freshness,
            String itemId,
            int count
    ) {
        return new InventorySnapshot(
                "test",
                container.id(),
                container.dimension(),
                START,
                freshness,
                List.of(new InventoryItem(itemId, count, InventoryItemKind.LOOSE))
        );
    }

    private static final class RecordingTransfer implements ItemTransferPort {
        private final List<Request> started = new ArrayList<>();

        @Override
        public AutomationOperationResult start(
                String operationKey,
                Request request
        ) {
            started.add(request);
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.paused("manual recovery");
        }
    }

    private static final class TestContainerInteraction
            implements ContainerInteraction, ItemTransferPort {
        private final List<Request> starts = new ArrayList<>();

        @Override
        public void prepare(ContainerRef container) {
        }

        @Override
        public Optional<ContainerView> observe(ContainerRef container) {
            return Optional.empty();
        }

        @Override
        public ContainerAttempt begin(
                ContainerTransfer transfer,
                long baselineRevision
        ) {
            throw new AssertionError("unused");
        }

        @Override
        public ContainerAttemptResult poll(ContainerAttempt attempt) {
            throw new AssertionError("unused");
        }

        @Override
        public void cancel(ContainerAttempt attempt) {
        }

        @Override
        public AutomationOperationResult start(
                String operationKey,
                Request request
        ) {
            starts.add(request);
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.paused("manual recovery");
        }
    }

    private static final class TestScheduler implements ClientActionScheduler {
        private final boolean clientThread;

        private TestScheduler(boolean clientThread) {
            this.clientThread = clientThread;
        }

        @Override
        public boolean isClientThread() {
            return clientThread;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            return CompletableFuture.completedFuture(action.get());
        }
    }

    private static final class NoopNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class StubPickupPort implements PickupWorkflowPort {
        @Override
        public AutomationOperationResult start(
                String operationKey,
                Request request
        ) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.paused("manual recovery");
        }
    }

    private static final class StubMaterialPort implements MaterialAcquisitionPort {
        @Override
        public AutomationOperationResult start(
                String operationKey,
                Request request
        ) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.paused("manual recovery");
        }
    }
}
