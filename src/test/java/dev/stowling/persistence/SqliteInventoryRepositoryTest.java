package dev.stowling.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.ContainerRef;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteInventoryRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsNestedInventoryAndFreshnessAcrossRestart() {
        Path path = tempDir.resolve("inventory.db");
        ContainerRef cache = PersistenceFixtures.container("cache-' OR 1=1 --");
        InventoryItem loose = item("minecraft:cobblestone", 64, InventoryItemKind.LOOSE);
        InventoryItem box = item("minecraft:shulker_box", 1, InventoryItemKind.BOXED);
        InventorySnapshot cacheSnapshot = PersistenceFixtures.snapshot(
                cache,
                InventoryFreshness.FRESH,
                List.of(loose, box),
                PersistenceFixtures.CAPTURED_AT
        );
        InventoryItem boxedLoose = item(
                "minecraft:cobblestone",
                32,
                InventoryItemKind.BOXED
        );
        InventoryItem unknown = item(
                "minecraft:unknown_fragment",
                5,
                InventoryItemKind.UNKNOWN
        );
        NestedInventoryScope nested = new NestedInventoryScope(
                cache.id() + "/shulker/0",
                Optional.of(cache.id()),
                List.of(boxedLoose, unknown)
        );
        SqliteInventoryRepository repository = new SqliteInventoryRepository(
                new StowlingDatabase(path)
        );
        repository.replaceSnapshot(
                cache,
                cacheSnapshot,
                PersistenceFixtures.OBSERVED_AT,
                List.of(nested)
        );
        ContainerRef stale = PersistenceFixtures.container("stale");
        InventoryItem redstone = item("minecraft:redstone", 10, InventoryItemKind.LOOSE);
        InventorySnapshot staleSnapshot = PersistenceFixtures.snapshot(
                stale,
                InventoryFreshness.STALE,
                List.of(redstone),
                PersistenceFixtures.CAPTURED_AT
        );
        repository.replaceSnapshot(stale, staleSnapshot, PersistenceFixtures.OBSERVED_AT);

        SqliteInventoryRepository reopened = new SqliteInventoryRepository(
                new StowlingDatabase(path)
        );
        StoredInventorySnapshot loaded = reopened.findSnapshot(cache.id()).orElseThrow();

        assertEquals(cacheSnapshot, loaded.snapshot());
        assertEquals(List.of(nested), loaded.nestedScopes());
        InventorySnapshot loadedStale = reopened.findSnapshot("stale")
                .orElseThrow()
                .snapshot();
        assertEquals(InventoryFreshness.STALE, loadedStale.freshness());
        Map<String, InventoryAggregate> aggregates = index(reopened.aggregateItems());
        InventoryAggregate looseCobblestone = aggregates.get(
                "minecraft:cobblestone/LOOSE"
        );
        InventoryAggregate boxedCobblestone = aggregates.get(
                "minecraft:cobblestone/BOXED"
        );
        assertEquals(64, looseCobblestone.totalCount());
        assertEquals(64, looseCobblestone.freshCount());
        assertEquals(32, boxedCobblestone.totalCount());
        assertEquals(32, boxedCobblestone.freshCount());
        assertEquals(5, aggregates.get("minecraft:unknown_fragment/UNKNOWN").freshCount());
        assertEquals(10, aggregates.get("minecraft:redstone/LOOSE").staleCount());
    }

    @Test
    void snapshotReplacementRollsBackWhenWritingAnEntryFails() throws Exception {
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve("rollback.db"));
        SqliteInventoryRepository repository = new SqliteInventoryRepository(database);
        ContainerRef container = PersistenceFixtures.container("cache");
        InventoryItem stone = item("minecraft:stone", 64, InventoryItemKind.LOOSE);
        InventorySnapshot original = PersistenceFixtures.snapshot(
                container,
                InventoryFreshness.FRESH,
                List.of(stone),
                PersistenceFixtures.CAPTURED_AT
        );
        repository.replaceSnapshot(
                container,
                original,
                PersistenceFixtures.OBSERVED_AT
        );
        createFailureTrigger(database);
        InventoryItem unobtainium = item(
                "minecraft:unobtainium",
                1,
                InventoryItemKind.LOOSE
        );
        InventorySnapshot failing = PersistenceFixtures.snapshot(
                container,
                InventoryFreshness.FRESH,
                List.of(unobtainium),
                PersistenceFixtures.CAPTURED_AT.plusSeconds(30)
        );

        Instant retryObservedAt = PersistenceFixtures.OBSERVED_AT.plusSeconds(35);
        assertThrows(PersistenceException.class,
                () -> repository.replaceSnapshot(container, failing, retryObservedAt));

        InventorySnapshot loaded = repository.findSnapshot(container.id())
                .orElseThrow()
                .snapshot();
        assertEquals(original, loaded);
    }

    @Test
    void exactAggregationTreatsInjectionShapedItemIdsAsData() {
        SqliteInventoryRepository repository = new SqliteInventoryRepository(
                new StowlingDatabase(tempDir.resolve("query.db"))
        );
        ContainerRef container = PersistenceFixtures.container("cache");
        String itemId = "minecraft:stone' OR 1=1 --";
        InventoryItem injected = item(itemId, 3, InventoryItemKind.UNKNOWN);
        InventorySnapshot injectedSnapshot = PersistenceFixtures.snapshot(
                container,
                InventoryFreshness.UNKNOWN,
                List.of(injected),
                PersistenceFixtures.CAPTURED_AT
        );
        repository.replaceSnapshot(
                container,
                injectedSnapshot,
                PersistenceFixtures.OBSERVED_AT
        );

        List<InventoryAggregate> aggregates = repository.aggregateItem(itemId);

        assertEquals(1, aggregates.size());
        assertEquals(itemId, aggregates.getFirst().itemId());
        assertEquals(3, aggregates.getFirst().unknownCount());
        assertTrue(repository.aggregateItem("minecraft:dirt").isEmpty());
    }

    @Test
    void rejectsAnUnknownNestedParentWithoutReplacingExistingSnapshot() {
        SqliteInventoryRepository repository = new SqliteInventoryRepository(
                new StowlingDatabase(tempDir.resolve("parent.db"))
        );
        ContainerRef container = PersistenceFixtures.container("cache");
        InventorySnapshot original = PersistenceFixtures.snapshot(
                container,
                InventoryFreshness.FRESH,
                List.of(),
                PersistenceFixtures.CAPTURED_AT
        );
        repository.replaceSnapshot(
                container,
                original,
                PersistenceFixtures.OBSERVED_AT
        );
        NestedInventoryScope invalid = new NestedInventoryScope(
                "child",
                Optional.of("missing"),
                List.of()
        );

        List<NestedInventoryScope> invalidScopes = List.of(invalid);
        assertThrows(IllegalArgumentException.class,
                () -> repository.replaceSnapshot(
                        container, original, PersistenceFixtures.OBSERVED_AT, invalidScopes
                ));

        InventorySnapshot loaded = repository.findSnapshot(container.id())
                .orElseThrow()
                .snapshot();
        assertEquals(original, loaded);
    }

    private static InventoryItem item(String itemId, int count, InventoryItemKind kind) {
        return new InventoryItem(itemId, count, kind);
    }

    private static Map<String, InventoryAggregate> index(List<InventoryAggregate> values) {
        Map<String, InventoryAggregate> result = new HashMap<>();
        for (InventoryAggregate value : values) {
            result.put(value.itemId() + "/" + value.kind(), value);
        }
        return Map.copyOf(result);
    }

    private static void createFailureTrigger(StowlingDatabase database) throws Exception {
        String sql = """
                CREATE TRIGGER reject_unobtainium
                BEFORE INSERT ON inventory_entries
                WHEN NEW.item_id = 'minecraft:unobtainium'
                BEGIN
                    SELECT RAISE(ABORT, 'forced test failure');
                END
                """;
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
