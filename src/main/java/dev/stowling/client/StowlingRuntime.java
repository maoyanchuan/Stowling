package dev.stowling.client;

import baritone.api.BaritoneAPI;
import dev.stowling.Stowling;
import dev.stowling.api.StowlingHttpServer;
import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.SystemAutomationClock;
import dev.stowling.automation.TickPackingPort;
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
import dev.stowling.control.StowlingControlServiceImpl;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import dev.stowling.headless.HeadlessControlBridge;
import dev.stowling.persistence.SqliteAutomationCheckpointStore;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StowlingDatabase;
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

    private StowlingRuntime(
            Path configPath,
            ConfigLoader configLoader,
            StowlingControlServiceImpl service,
            StowlingHttpServer server
    ) {
        this.configPath = configPath;
        this.configLoader = configLoader;
        this.service = service;
        this.server = server;
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
                scheduler
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
        SqliteAutomationCheckpointStore checkpoints =
                new SqliteAutomationCheckpointStore(tasks);
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
                new SystemAutomationClock()
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
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config,
                tasks,
                inventories,
                inventorySource,
                pickupFactory,
                scheduler::execute,
                () -> configLoader.load(configPath),
                loaded -> inventories.upsertContainers(allContainers(loaded)),
                new ConfigFileStore(configPath, configLoader)
        );

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
                server
        );
        server.start();
        HeadlessControlBridge.install(service);
        service.recoverPendingPickups();
        Stowling.LOGGER.info("Stowling control runtime started on loopback port {}", port);
        return runtime;
    }

    public void tick() {
        service.tickOnClientThread();
    }

    @Override
    public void close() {
        server.stop();
        HeadlessControlBridge.clear(service);
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
}
