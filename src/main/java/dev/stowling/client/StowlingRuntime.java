package dev.stowling.client;

import baritone.api.BaritoneAPI;
import dev.stowling.Stowling;
import dev.stowling.automation.NavigationPort;
import dev.stowling.client.automation.MinecraftCategoryZoneResolver;
import dev.stowling.client.automation.MinecraftContainerInspectionPort;
import dev.stowling.client.automation.MinecraftContainerRegionScanner;
import dev.stowling.client.automation.MinecraftContainerTransferPort;
import dev.stowling.client.automation.MinecraftContainerWithdrawalPort;
import dev.stowling.client.automation.ClientThreadNavigationPort;
import dev.stowling.client.automation.MinecraftDroppedItemObserver;
import dev.stowling.client.automation.MinecraftFoodConsumptionPort;
import dev.stowling.client.interaction.BaritoneAutomationNavigation;
import dev.stowling.client.interaction.MinecraftClientActionScheduler;
import dev.stowling.client.interaction.MinecraftWorldObserver;
import dev.stowling.control.InventoryInfo;
import dev.stowling.control.InventoryItemInfo;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.persistence.StowlingDatabase;
import dev.stowling.v2.api.V2ControlServer;
import dev.stowling.v2.cache.CategoryMarkerCacheStore;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.config.StartupConfiguration;
import dev.stowling.v2.control.V2ControlService;
import dev.stowling.v2.control.V2ControlServiceImpl;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.modules.StandardTaskModuleFactories;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class StowlingRuntime implements AutoCloseable {
    public static final int DEFAULT_CONTROL_PORT = 8765;

    private final TaskRunManager taskRuns;
    private final ClientShutdownSequence shutdownSequence;

    private StowlingRuntime(
            TaskRunManager taskRuns,
            List<ClientShutdownSequence.ShutdownStep> shutdownSteps
    ) {
        this.taskRuns = taskRuns;
        this.shutdownSequence = new ClientShutdownSequence(shutdownSteps);
    }

    public static StowlingRuntime start(Minecraft minecraft) throws IOException {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        Files.createDirectories(configDirectory);
        var configuration = StartupConfiguration.load(configDirectory);
        int port = Integer.getInteger("stowling.http.port", DEFAULT_CONTROL_PORT);
        Path tokenFile = configDirectory.resolve("stowling-api.token");
        if (!configuration.issues().isEmpty()) {
            V2ControlServer server = V2ControlServer.recoveryWithTokenFile(
                    configuration.issues(), tokenFile, port
            );
            server.start();
            Stowling.LOGGER.warn("Stowling configuration requires repair. Open the console "
                    + "on loopback port {} for details.", port);
            return new StowlingRuntime(null, List.of(httpShutdown(server)));
        }
        var configs = configuration.configs();
        var storageSettings = configuration.storageSettings();

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
        MinecraftContainerRegionScanner containerScanner =
                new MinecraftContainerRegionScanner(minecraft, scheduler);
        CategoryMarkerCacheStore categoryMarkerCache =
                new CategoryMarkerCacheStore(configDirectory.resolve(
                        "stowling-category-marker-cache.json"
                ));
        TaskModuleRuntimeRegistry moduleRuntime = new TaskModuleRuntimeRegistry(
                navigationPort,
                new MinecraftDroppedItemObserver(minecraft, scheduler),
                containerScanner,
                new MinecraftContainerTransferPort(minecraft, scheduler, navigationPort),
                new MinecraftContainerWithdrawalPort(minecraft, scheduler, navigationPort),
                new MinecraftContainerInspectionPort(minecraft, scheduler, navigationPort),
                categoryMarkerCache,
                new MinecraftFoodConsumptionPort(minecraft, scheduler),
                new MinecraftCategoryZoneResolver(
                        minecraft,
                        scheduler,
                        containerScanner,
                        CategoryRuleRegistry.standard()
                ),
                StandardTaskModuleFactories.create()
        );
        var warehouse = new dev.stowling.client.automation.ChestTrackerInventorySource(
                minecraft, scheduler, configs);
        moduleRuntime.setStorageAutomation(
                new dev.stowling.client.automation.MinecraftStorageAutomation(
                        minecraft, storageSettings, containerScanner, warehouse));
        TaskRunManager taskRuns = new TaskRunManager(
                navigationPort,
                moduleRuntime
        );
        V2ControlService service = new V2ControlServiceImpl(
                configs,
                taskRuns,
                () -> inventory(inventories),
                storageSettings,
                warehouse
        );

        V2ControlServer server = V2ControlServer.loopbackWithTokenFile(service, tokenFile, port);
        var baritoneCleanup = new BaritoneClientCleanup(
                BaritoneAPI.getProvider().getPrimaryBaritone());
        StowlingRuntime runtime = new StowlingRuntime(taskRuns, List.of(
                new ClientShutdownSequence.ShutdownStep("Baritone", baritoneCleanup::close),
                httpShutdown(server)
        ));
        server.start();
        Stowling.LOGGER.info(
                "Stowling movement runtime started on loopback port {}",
                port
        );
        return runtime;
    }

    public void tick() {
        if (taskRuns != null) {
            taskRuns.tick();
        }
    }

    @Override
    public void close() {
        if (taskRuns != null) {
            taskRuns.cancelAll();
        }
        shutdownSequence.close();
    }

    private static ClientShutdownSequence.ShutdownStep httpShutdown(V2ControlServer server) {
        return new ClientShutdownSequence.ShutdownStep("HTTP server", server::stop);
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
