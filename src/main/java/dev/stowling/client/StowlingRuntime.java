package dev.stowling.client;

import baritone.api.BaritoneAPI;
import dev.stowling.Stowling;
import dev.stowling.automation.NavigationPort;
import dev.stowling.client.automation.ClientThreadNavigationPort;
import dev.stowling.client.interaction.BaritoneAutomationNavigation;
import dev.stowling.client.interaction.MinecraftClientActionScheduler;
import dev.stowling.client.interaction.MinecraftWorldObserver;
import dev.stowling.control.InventoryInfo;
import dev.stowling.control.InventoryItemInfo;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.persistence.StowlingDatabase;
import dev.stowling.v2.api.V2ControlServer;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.control.V2ControlService;
import dev.stowling.v2.control.V2ControlServiceImpl;
import dev.stowling.v2.runtime.TaskRunManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class StowlingRuntime implements AutoCloseable {
    public static final int DEFAULT_CONTROL_PORT = 8765;

    private final TaskRunManager taskRuns;
    private final V2ControlServer server;
    private final ClientShutdownSequence shutdownSequence;

    private StowlingRuntime(
            TaskRunManager taskRuns,
            V2ControlServer server,
            BaritoneClientCleanup baritoneCleanup
    ) {
        this.taskRuns = taskRuns;
        this.server = server;
        this.shutdownSequence = new ClientShutdownSequence(List.of(
                new ClientShutdownSequence.ShutdownStep(
                        "Baritone",
                        baritoneCleanup::close
                ),
                new ClientShutdownSequence.ShutdownStep(
                        "HTTP server",
                        server::stop
                )
        ));
    }

    public static StowlingRuntime start(Minecraft minecraft) throws IOException {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Files.createDirectories(configDirectory);
        ConfigStore configs = new ConfigStore(configDirectory.resolve("stowling.json"));
        configs.loadOrReset();

        StowlingDatabase database = new StowlingDatabase(
                configDirectory.resolve("stowling.db")
        );
        database.ensureMigrated();
        SqliteInventoryRepository inventories = new SqliteInventoryRepository(database);

        MinecraftClientActionScheduler scheduler = new MinecraftClientActionScheduler(
                minecraft
        );
        MinecraftWorldObserver worldObserver = new MinecraftWorldObserver(
                minecraft,
                scheduler
        );
        BaritoneAutomationNavigation navigation = new BaritoneAutomationNavigation(
                new dev.stowling.navigation.BaritoneNavigationService(
                        BaritoneAPI.getProvider()
                ),
                worldObserver
        );
        NavigationPort navigationPort = new ClientThreadNavigationPort(
                navigation,
                scheduler
        );
        TaskRunManager taskRuns = new TaskRunManager(navigationPort);
        V2ControlService service = new V2ControlServiceImpl(
                configs,
                taskRuns,
                () -> inventory(inventories)
        );

        int port = Integer.getInteger("stowling.http.port", DEFAULT_CONTROL_PORT);
        V2ControlServer server = V2ControlServer.loopbackWithTokenFile(
                service,
                configDirectory.resolve("stowling-api.token"),
                port
        );
        StowlingRuntime runtime = new StowlingRuntime(
                taskRuns,
                server,
                new BaritoneClientCleanup(
                        BaritoneAPI.getProvider().getPrimaryBaritone()
                )
        );
        server.start();
        Stowling.LOGGER.info(
                "Stowling movement runtime started on loopback port {}",
                port
        );
        return runtime;
    }

    public void tick() {
        taskRuns.tick();
    }

    @Override
    public void close() {
        taskRuns.cancelAll();
        shutdownSequence.close();
    }

    private static List<InventoryInfo> inventory(
            SqliteInventoryRepository inventories
    ) {
        return inventories.findAllSnapshots().stream()
                .map(StowlingRuntime::inventoryInfo)
                .toList();
    }

    private static InventoryInfo inventoryInfo(StoredInventorySnapshot stored) {
        var snapshot = stored.snapshot();
        return new InventoryInfo(
                snapshot.sourceId(),
                snapshot.containerId(),
                snapshot.dimension(),
                snapshot.capturedAt(),
                snapshot.freshness().name(),
                snapshot.items().stream()
                        .map(item -> new InventoryItemInfo(
                                item.itemId(),
                                item.count(),
                                item.kind().name()
                        ))
                        .toList()
        );
    }
}
