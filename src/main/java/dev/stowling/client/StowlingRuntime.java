package dev.stowling.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import dev.stowling.Stowling;
import dev.stowling.api.StowlingHttpServer;
import dev.stowling.automation.AutomationCheckpointStore;
import dev.stowling.automation.AutomationClock;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.CoordinateResolver;
import dev.stowling.automation.CraftingPort;
import dev.stowling.automation.InMemoryAutomationCheckpointStore;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.MaterialAcquisitionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.PackingPort;
import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.PickupAutomationPlan;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.automation.PlayerInventoryAdapter;
import dev.stowling.automation.SystemAutomationClock;
import dev.stowling.automation.TickPackingPort;
import dev.stowling.automation.WorldObserver;
import dev.stowling.automation.module.DefaultAutomationModuleRegistryFactory;
import dev.stowling.client.automation.ClientPickupWorkflowPort;
import dev.stowling.client.automation.ClientThreadInventoryQueryPort;
import dev.stowling.client.automation.ClientThreadItemTransferPort;
import dev.stowling.client.automation.ClientThreadNavigationPort;
import dev.stowling.client.automation.ConfigCoordinateResolver;
import dev.stowling.client.automation.SqliteMaterialAcquisitionPort;
import dev.stowling.client.automation.StowlingAutomationRuntimeFactory;
import dev.stowling.client.automation.UnsupportedClientCraftingPort;
import dev.stowling.client.interaction.BaritoneAutomationNavigation;
import dev.stowling.client.interaction.MinecraftClientActionScheduler;
import dev.stowling.client.interaction.MinecraftContainerInteraction;
import dev.stowling.client.interaction.MinecraftPackingStationPort;
import dev.stowling.client.interaction.MinecraftWorldObserver;
import dev.stowling.client.interaction.ScreenInventorySource;
import dev.stowling.config.ConfigLoader;
import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.StowlingConfig;
import dev.stowling.control.PickupRuntimeFactory;
import dev.stowling.control.AutomationRuntimeFactory;
import dev.stowling.control.StowlingControlServiceImpl;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.StorageRoute;
import dev.stowling.headless.HeadlessControlBridge;
import dev.stowling.persistence.SqliteAutomationCheckpointStore;
import dev.stowling.persistence.SqliteGenericAutomationCheckpointStore;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StowlingDatabase;
import dev.stowling.persistence.SqliteTaskScheduleStore;
import java.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class StowlingRuntime implements AutoCloseable {
    public static final int DEFAULT_CONTROL_PORT = 8765;

    private final Path configPath;
    private final ConfigLoader configLoader;
    private final StowlingControlServiceImpl service;
    private final StowlingHttpServer server;
    private final ClientShutdownSequence shutdownSequence;

    private StowlingRuntime(
            Path configPath,
            ConfigLoader configLoader,
            StowlingControlServiceImpl service,
            StowlingHttpServer server,
            BaritoneClientCleanup baritoneCleanup
    ) {
        this.configPath = configPath;
        this.configLoader = configLoader;
        this.service = service;
        this.server = server;
        this.shutdownSequence = new ClientShutdownSequence(List.of(
                new ClientShutdownSequence.ShutdownStep(
                        "Baritone",
                        baritoneCleanup::close
                ),
                new ClientShutdownSequence.ShutdownStep(
                        "HTTP server",
                        server::stop
                ),
                new ClientShutdownSequence.ShutdownStep(
                        "headless bridge",
                        () -> HeadlessControlBridge.clear(service)
                )
        ));
    }

    public static StowlingRuntime start(Minecraft minecraft) throws IOException {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Files.createDirectories(configDirectory);
        Path configPath = configDirectory.resolve("stowling.json");
        ConfigLoader configLoader = new ConfigLoader();
        StowlingConfig config = loadOrCreateConfig(configLoader, configPath);

        StowlingDatabase database = new StowlingDatabase(
                configDirectory.resolve("stowling.db")
        );
        database.ensureMigrated();
        SqliteTaskRepository tasks = new SqliteTaskRepository(database);
        SqliteInventoryRepository inventories = new SqliteInventoryRepository(database);
        inventories.upsertContainers(allContainers(config));

        MinecraftClientActionScheduler scheduler = new MinecraftClientActionScheduler(
                minecraft
        );
        MinecraftWorldObserver worldObserver = new MinecraftWorldObserver(
                minecraft,
                scheduler
        );
        MinecraftContainerInteraction containers = new MinecraftContainerInteraction(
                minecraft,
                scheduler,
                inventories::findContainer
        );
        MinecraftPackingStationPort packingStations = new MinecraftPackingStationPort(
                minecraft,
                scheduler,
                containers
        );
        ScreenInventorySource inventorySource = new ScreenInventorySource(
                scheduler,
                containers
        );
        SystemAutomationClock automationClock = new SystemAutomationClock();
        SqliteAutomationCheckpointStore checkpoints =
                new SqliteAutomationCheckpointStore(tasks);
        IBaritone primaryBaritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        BaritoneAutomationNavigation navigation = new BaritoneAutomationNavigation(
                new dev.stowling.navigation.BaritoneNavigationService(
                BaritoneAPI.getProvider()
        ),
                worldObserver
        );
        TickPackingPort packingPort = new TickPackingPort(
                () -> configLoader.load(configPath),
                worldObserver,
                navigation,
                containers,
                packingStations,
                automationClock
        );
        PickupRuntimeFactory pickupFactory = plan -> {
            SystemAutomationClock clock = new SystemAutomationClock();
            return new PickupAutomationEngine(
                    plan,
                    worldObserver,
                    worldObserver,
                    navigation,
                    containers,
                    packingPort,
                    clock,
                    checkpoints
            );
        };
        CoordinateResolver coordinates = new ConfigCoordinateResolver(
                () -> configLoader.load(configPath)
        );
        NavigationPort navigationPort = new ClientThreadNavigationPort(
                navigation,
                scheduler
        );
        ItemTransferPort transfers = new ClientThreadItemTransferPort(
                containers,
                scheduler
        );
        InventoryQueryPort inventory = new ClientThreadInventoryQueryPort(
                worldObserver,
                scheduler
        );
        MaterialAcquisitionPort materials = new SqliteMaterialAcquisitionPort(
                inventories,
                transfers
        );
        InMemoryAutomationCheckpointStore pickupCheckpoints =
                new InMemoryAutomationCheckpointStore();
        PickupWorkflowPort pickups = new ClientPickupWorkflowPort(
                () -> configLoader.load(configPath),
                plan -> createPickupEngine(
                plan,
                worldObserver,
                navigationPort,
                containers,
                packingPort,
                automationClock,
                pickupCheckpoints
        ),
                scheduler
        );
        CraftingPort crafting = new UnsupportedClientCraftingPort();
        DefaultAutomationModuleRegistryFactory moduleFactory =
                createModuleFactory(
                coordinates,
                navigationPort,
                automationClock,
                pickups,
                transfers,
                inventory,
                materials,
                crafting
        );
        SqliteGenericAutomationCheckpointStore genericCheckpoints =
                new SqliteGenericAutomationCheckpointStore(tasks);
        AutomationRuntimeFactory automationFactory =
                new StowlingAutomationRuntimeFactory(moduleFactory, genericCheckpoints);
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config,
                tasks,
                inventories,
                inventorySource,
                pickupFactory,
                scheduler::execute,
                () -> configLoader.load(configPath),
                loaded -> inventories.upsertContainers(allContainers(loaded)),
                new ConfigFileStore(configPath, configLoader),
                automationFactory
        );

        service.configureScheduling(
                new SqliteTaskScheduleStore(database),
                new ScheduledRegionItemProbe(minecraft, worldObserver, service::activeConfig),
                Clock.systemUTC()
        );
        service.recoverPendingPickups();

        int port = Integer.getInteger("stowling.http.port", DEFAULT_CONTROL_PORT);
        StowlingHttpServer server = StowlingHttpServer.loopbackWithTokenFile(
                service,
                configDirectory.resolve("stowling-api.token"),
                port
        );
        StowlingRuntime runtime = new StowlingRuntime(
                configPath,
                configLoader,
                service,
                server,
                new BaritoneClientCleanup(primaryBaritone)
        );
        server.start();
        HeadlessControlBridge.install(service);
        Stowling.LOGGER.info("Stowling control runtime started on loopback port {}", port);
        return runtime;
    }

    public void tick() {
        service.tickOnClientThread();
    }

    @Override
    public void close() {
        shutdownSequence.close();
    }

    private static PickupAutomationEngine createPickupEngine(
            PickupAutomationPlan plan,
            MinecraftWorldObserver worldObserver,
            NavigationPort navigation,
            ContainerInteraction containers,
            PackingPort packing,
            AutomationClock clock,
            AutomationCheckpointStore checkpoints
    ) {
        PlayerInventoryAdapter inventory = worldObserver;
        WorldObserver world = worldObserver;
        return new PickupAutomationEngine(
                plan,
                world,
                inventory,
                navigation,
                containers,
                packing,
                clock,
                checkpoints
        );
    }

    private static DefaultAutomationModuleRegistryFactory createModuleFactory(
            CoordinateResolver coordinates,
            NavigationPort navigation,
            AutomationClock clock,
            PickupWorkflowPort pickups,
            ItemTransferPort transfers,
            InventoryQueryPort inventory,
            MaterialAcquisitionPort materials,
            CraftingPort crafting
    ) {
        return new DefaultAutomationModuleRegistryFactory(
                coordinates,
                navigation,
                clock,
                pickups,
                transfers,
                inventory,
                materials,
                crafting
        );
    }

    private static StowlingConfig loadOrCreateConfig(
            ConfigLoader loader,
            Path configPath
    ) {
        if (Files.isRegularFile(configPath)) {
            return loader.load(configPath);
        }
        try (InputStream input = StowlingRuntime.class.getResourceAsStream(
                "/stowling.example.json"
        )) {
            if (input == null) {
                throw new IllegalStateException("Bundled Stowling config is unavailable");
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                StowlingConfig config = loader.load(reader);
                loader.write(configPath, config);
                return config;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Stowling config", exception);
        }
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
}
