package dev.stowling.persistence;

import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SqliteInventoryRepository {
    private static final String SNAPSHOTS_SQL = """
            SELECT
                container_id,
                source_id,
                dimension,
                captured_at,
                observed_at,
                freshness
            FROM inventory_snapshots
            ORDER BY container_id
            """;
    private static final String ITEMS_SQL = """
            SELECT item_id, item_kind, item_count
            FROM inventory_entries
            WHERE container_id = ? AND scope_id = ?
            ORDER BY item_id, item_kind
            """;

    private final StowlingDatabase database;

    public SqliteInventoryRepository(StowlingDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        database.ensureMigrated();
    }

    public List<StoredInventorySnapshot> findAllSnapshots() {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(SNAPSHOTS_SQL);
                ResultSet result = statement.executeQuery()) {
            List<StoredInventorySnapshot> snapshots = new ArrayList<>();
            while (result.next()) {
                InventorySnapshot snapshot = readSnapshot(connection, result);
                snapshots.add(new StoredInventorySnapshot(
                        snapshot,
                        Instant.ofEpochMilli(result.getLong("observed_at"))
                ));
            }
            return List.copyOf(snapshots);
        } catch (SQLException exception) {
            throw new PersistenceException("failed to list inventory snapshots", exception);
        }
    }

    private static InventorySnapshot readSnapshot(
            Connection connection,
            ResultSet result
    ) throws SQLException {
        String containerId = result.getString("container_id");
        return new InventorySnapshot(
                result.getString("source_id"),
                containerId,
                result.getString("dimension"),
                Instant.ofEpochMilli(result.getLong("captured_at")),
                InventoryFreshness.valueOf(result.getString("freshness")),
                readItems(connection, containerId)
        );
    }

    private static List<InventoryItem> readItems(
            Connection connection,
            String containerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ITEMS_SQL)) {
            statement.setString(1, containerId);
            statement.setString(2, containerId);
            try (ResultSet result = statement.executeQuery()) {
                List<InventoryItem> items = new ArrayList<>();
                while (result.next()) {
                    items.add(new InventoryItem(
                            result.getString("item_id"),
                            result.getInt("item_count"),
                            InventoryItemKind.valueOf(result.getString("item_kind"))
                    ));
                }
                return List.copyOf(items);
            }
        }
    }
}
