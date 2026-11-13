package dev.stowling.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PickupSite;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySource;
import dev.stowling.persistence.NestedInventoryScope;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.persistence.StowlingDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StowlingControlServiceImplTest {
    private static final String DIMENSION = "minecraft:overworld";

    @TempDir
    Path tempDir;

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
                Objects::requireNonNull
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
}
