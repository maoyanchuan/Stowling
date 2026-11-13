package dev.stowling.persistence;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.InventorySnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class SqliteInventoryRepository {
    private static final String UPSERT_CONTAINER_SQL = """
            INSERT INTO containers(
                container_id,
                dimension,
                block_x,
                block_y,
                block_z,
                container_type,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(container_id) DO UPDATE SET
                dimension = excluded.dimension,
                block_x = excluded.block_x,
                block_y = excluded.block_y,
                block_z = excluded.block_z,
                container_type = excluded.container_type,
                updated_at = excluded.updated_at
            """;
    private static final String SNAPSHOT_SELECT = """
            SELECT
                snapshot.container_id,
                snapshot.source_id,
                snapshot.dimension,
                snapshot.captured_at,
                snapshot.observed_at,
                snapshot.freshness
            FROM inventory_snapshots snapshot
            """;

    private final StowlingDatabase database;

    public SqliteInventoryRepository(StowlingDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        database.ensureMigrated();
    }

    public void upsertContainer(ContainerRef container) {
        Objects.requireNonNull(container, "container");
        try (Connection connection = database.openConnection()) {
            upsertContainer(connection, container, Instant.now());
        } catch (SQLException exception) {
            throw new PersistenceException("failed to save container", exception);
        }
    }

    public void upsertContainers(Collection<ContainerRef> containers) {
        Objects.requireNonNull(containers, "containers");
        Instant updatedAt = Instant.now();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (ContainerRef container : containers) {
                    upsertContainer(connection, container, updatedAt);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to save containers", exception);
        }
    }

    public Optional<ContainerRef> findContainer(String id) {
        Objects.requireNonNull(id, "id");
        String sql = """
                SELECT container_id, dimension, block_x, block_y, block_z, container_type
                FROM containers
                WHERE container_id = ?
                """;
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readContainer(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to find container", exception);
        }
    }

    public List<ContainerRef> findContainers() {
        String sql = """
                SELECT container_id, dimension, block_x, block_y, block_z, container_type
                FROM containers
                ORDER BY container_id
                """;
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<ContainerRef> containers = new ArrayList<>();
            while (result.next()) {
                containers.add(readContainer(result));
            }
            return List.copyOf(containers);
        } catch (SQLException exception) {
            throw new PersistenceException("failed to list containers", exception);
        }
    }

    public void replaceSnapshot(
            ContainerRef container,
            InventorySnapshot snapshot,
            Instant observedAt
    ) {
        replaceSnapshot(container, snapshot, observedAt, List.of());
    }

    public void replaceSnapshot(
            ContainerRef container,
            InventorySnapshot snapshot,
            Instant observedAt,
            List<NestedInventoryScope> nestedScopes
    ) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(nestedScopes, "nestedScopes");
        requireMatchingContainer(container, snapshot);
        StoredInventorySnapshot stored = new StoredInventorySnapshot(
                snapshot,
                observedAt,
                nestedScopes
        );
        Map<String, NestedInventoryScope> scopes = validateScopes(
                snapshot.containerId(),
                stored.nestedScopes()
        );
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertContainer(connection, container, observedAt);
                replaceSnapshotRows(connection, stored, scopes);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to replace inventory snapshot", exception);
        }
    }

    public Optional<StoredInventorySnapshot> findSnapshot(String containerId) {
        Objects.requireNonNull(containerId, "containerId");
        String sql = SNAPSHOT_SELECT + " WHERE snapshot.container_id = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, containerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                InventorySnapshot root = readSnapshot(connection, result);
                Instant observedAt = Instant.ofEpochMilli(result.getLong("observed_at"));
                List<NestedInventoryScope> scopes = readNestedScopes(connection, containerId);
                return Optional.of(new StoredInventorySnapshot(root, observedAt, scopes));
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to find inventory snapshot", exception);
        }
    }

    public List<StoredInventorySnapshot> findAllSnapshots() {
        String sql = SNAPSHOT_SELECT + " ORDER BY snapshot.container_id";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<StoredInventorySnapshot> snapshots = new ArrayList<>();
            while (result.next()) {
                InventorySnapshot root = readSnapshot(connection, result);
                Instant observedAt = Instant.ofEpochMilli(result.getLong("observed_at"));
                List<NestedInventoryScope> scopes = readNestedScopes(
                        connection,
                        root.containerId()
                );
                snapshots.add(new StoredInventorySnapshot(root, observedAt, scopes));
            }
            return List.copyOf(snapshots);
        } catch (SQLException exception) {
            throw new PersistenceException("failed to list inventory snapshots", exception);
        }
    }

    public List<InventoryAggregate> aggregateItems() {
        String sql = """
                SELECT
                    entry.item_id,
                    entry.item_kind,
                    SUM(entry.item_count) AS total_count,
                    SUM(CASE WHEN snapshot.freshness = 'FRESH'
                        THEN entry.item_count ELSE 0 END) AS fresh_count,
                    SUM(CASE WHEN snapshot.freshness = 'STALE'
                        THEN entry.item_count ELSE 0 END) AS stale_count,
                    SUM(CASE WHEN snapshot.freshness = 'UNKNOWN'
                        THEN entry.item_count ELSE 0 END) AS unknown_count
                FROM inventory_entries entry
                JOIN inventory_snapshots snapshot
                    ON snapshot.container_id = entry.container_id
                GROUP BY entry.item_id, entry.item_kind
                ORDER BY entry.item_id, entry.item_kind
                """;
        return readAggregates(sql, Optional.empty());
    }

    public List<InventoryAggregate> aggregateItem(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        String sql = """
                SELECT
                    entry.item_id,
                    entry.item_kind,
                    SUM(entry.item_count) AS total_count,
                    SUM(CASE WHEN snapshot.freshness = 'FRESH'
                        THEN entry.item_count ELSE 0 END) AS fresh_count,
                    SUM(CASE WHEN snapshot.freshness = 'STALE'
                        THEN entry.item_count ELSE 0 END) AS stale_count,
                    SUM(CASE WHEN snapshot.freshness = 'UNKNOWN'
                        THEN entry.item_count ELSE 0 END) AS unknown_count
                FROM inventory_entries entry
                JOIN inventory_snapshots snapshot
                    ON snapshot.container_id = entry.container_id
                WHERE entry.item_id = ?
                GROUP BY entry.item_id, entry.item_kind
                ORDER BY entry.item_kind
                """;
        return readAggregates(sql, Optional.of(itemId));
    }

    public void deleteSnapshot(String containerId) {
        Objects.requireNonNull(containerId, "containerId");
        String sql = "DELETE FROM inventory_snapshots WHERE container_id = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, containerId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new PersistenceException("failed to delete inventory snapshot", exception);
        }
    }

    private void replaceSnapshotRows(
            Connection connection,
            StoredInventorySnapshot stored,
            Map<String, NestedInventoryScope> scopes
    ) throws SQLException {
        InventorySnapshot snapshot = stored.snapshot();
        String deleteSql = "DELETE FROM inventory_snapshots WHERE container_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setString(1, snapshot.containerId());
            statement.executeUpdate();
        }
        insertSnapshot(connection, snapshot, stored.observedAt());
        insertScope(connection, snapshot.containerId(), snapshot.containerId(), Optional.empty());
        insertItems(connection, snapshot.containerId(), snapshot.containerId(), snapshot.items());
        insertNestedScopes(connection, snapshot.containerId(), scopes);
    }

    private static void insertSnapshot(
            Connection connection,
            InventorySnapshot snapshot,
            Instant observedAt
    ) throws SQLException {
        String sql = """
                INSERT INTO inventory_snapshots(
                    container_id,
                    source_id,
                    dimension,
                    captured_at,
                    observed_at,
                    freshness
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshot.containerId());
            statement.setString(2, snapshot.sourceId());
            statement.setString(3, snapshot.dimension());
            statement.setLong(4, snapshot.capturedAt().toEpochMilli());
            statement.setLong(5, observedAt.toEpochMilli());
            statement.setString(6, snapshot.freshness().name());
            statement.executeUpdate();
        }
    }

    private static void insertNestedScopes(
            Connection connection,
            String containerId,
            Map<String, NestedInventoryScope> scopes
    ) throws SQLException {
        Set<String> availableParents = new HashSet<>();
        availableParents.add(containerId);
        int remaining = scopes.size();
        while (remaining > 0) {
            boolean progressed = false;
            for (NestedInventoryScope scope : scopes.values()) {
                String parent = scope.parentScopeId().orElse(containerId);
                if (availableParents.contains(scope.scopeId())
                        || !availableParents.contains(parent)) {
                    continue;
                }
                insertScope(
                        connection,
                        containerId,
                        scope.scopeId(),
                        Optional.of(parent)
                );
                insertItems(connection, containerId, scope.scopeId(), scope.items());
                availableParents.add(scope.scopeId());
                remaining--;
                progressed = true;
            }
            if (!progressed) {
                throw new IllegalArgumentException("nested inventory scopes contain a cycle");
            }
        }
    }

    private static void insertScope(
            Connection connection,
            String containerId,
            String scopeId,
            Optional<String> parentScopeId
    ) throws SQLException {
        String sql = """
                INSERT INTO inventory_scopes(container_id, scope_id, parent_scope_id)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, containerId);
            statement.setString(2, scopeId);
            if (parentScopeId.isPresent()) {
                statement.setString(3, parentScopeId.orElseThrow());
            } else {
                statement.setNull(3, Types.VARCHAR);
            }
            statement.executeUpdate();
        }
    }

    private static void insertItems(
            Connection connection,
            String containerId,
            String scopeId,
            List<InventoryItem> items
    ) throws SQLException {
        List<InventoryItem> normalized = normalizeItems(items);
        String sql = """
                INSERT INTO inventory_entries(
                    container_id,
                    scope_id,
                    item_id,
                    item_kind,
                    item_count
                ) VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (InventoryItem item : normalized) {
                if (item.count() == 0) {
                    continue;
                }
                statement.setString(1, containerId);
                statement.setString(2, scopeId);
                statement.setString(3, item.itemId());
                statement.setString(4, item.kind().name());
                statement.setInt(5, item.count());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<InventoryItem> normalizeItems(List<InventoryItem> items) {
        Map<ItemKey, Integer> counts = new TreeMap<>((left, right) -> {
            int idComparison = left.itemId().compareTo(right.itemId());
            if (idComparison != 0) {
                return idComparison;
            }
            return left.kind().compareTo(right.kind());
        });
        for (InventoryItem item : items) {
            ItemKey key = new ItemKey(item.itemId(), item.kind());
            int current = counts.getOrDefault(key, 0);
            counts.put(key, Math.addExact(current, item.count()));
        }
        List<InventoryItem> normalized = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> entry : counts.entrySet()) {
            ItemKey key = entry.getKey();
            normalized.add(new InventoryItem(key.itemId(), entry.getValue(), key.kind()));
        }
        return List.copyOf(normalized);
    }

    private static Map<String, NestedInventoryScope> validateScopes(
            String rootId,
            List<NestedInventoryScope> scopes
    ) {
        Map<String, NestedInventoryScope> byId = new LinkedHashMap<>();
        for (NestedInventoryScope scope : scopes) {
            if (scope.scopeId().equals(rootId)) {
                throw new IllegalArgumentException("nested scope id must differ from root");
            }
            NestedInventoryScope previous = byId.putIfAbsent(scope.scopeId(), scope);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate nested scope id: " + scope.scopeId());
            }
        }
        Set<String> validParents = new HashSet<>(byId.keySet());
        validParents.add(rootId);
        for (NestedInventoryScope scope : byId.values()) {
            Optional<String> parent = scope.parentScopeId();
            if (parent.isPresent() && !validParents.contains(parent.orElseThrow())) {
                throw new IllegalArgumentException("unknown parent scope: " + parent.orElseThrow());
            }
        }
        return Map.copyOf(byId);
    }

    private static void requireMatchingContainer(
            ContainerRef container,
            InventorySnapshot snapshot
    ) {
        if (!container.id().equals(snapshot.containerId())) {
            throw new IllegalArgumentException("container id must match snapshot");
        }
        if (!container.dimension().equals(snapshot.dimension())) {
            throw new IllegalArgumentException("container dimension must match snapshot");
        }
    }

    private static void upsertContainer(
            Connection connection,
            ContainerRef container,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_CONTAINER_SQL)) {
            statement.setString(1, container.id());
            statement.setString(2, container.dimension());
            statement.setInt(3, container.position().x());
            statement.setInt(4, container.position().y());
            statement.setInt(5, container.position().z());
            statement.setString(6, container.type().name());
            statement.setLong(7, updatedAt.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static ContainerRef readContainer(ResultSet result) throws SQLException {
        return new ContainerRef(
                result.getString("container_id"),
                result.getString("dimension"),
                new BlockPosition(
                        result.getInt("block_x"),
                        result.getInt("block_y"),
                        result.getInt("block_z")
                ),
                ContainerType.valueOf(result.getString("container_type"))
        );
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
                readItems(connection, containerId, containerId)
        );
    }

    private static List<NestedInventoryScope> readNestedScopes(
            Connection connection,
            String containerId
    ) throws SQLException {
        String sql = """
                SELECT scope_id, parent_scope_id
                FROM inventory_scopes
                WHERE container_id = ? AND scope_id <> container_id
                ORDER BY scope_id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, containerId);
            try (ResultSet result = statement.executeQuery()) {
                List<NestedInventoryScope> scopes = new ArrayList<>();
                while (result.next()) {
                    scopes.add(new NestedInventoryScope(
                            result.getString("scope_id"),
                            Optional.ofNullable(result.getString("parent_scope_id")),
                            readItems(connection, containerId, result.getString("scope_id"))
                    ));
                }
                return List.copyOf(scopes);
            }
        }
    }

    private static List<InventoryItem> readItems(
            Connection connection,
            String containerId,
            String scopeId
    ) throws SQLException {
        String sql = """
                SELECT item_id, item_kind, item_count
                FROM inventory_entries
                WHERE container_id = ? AND scope_id = ?
                ORDER BY item_id, item_kind
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, containerId);
            statement.setString(2, scopeId);
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

    private List<InventoryAggregate> readAggregates(String sql, Optional<String> itemId) {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (itemId.isPresent()) {
                statement.setString(1, itemId.orElseThrow());
            }
            try (ResultSet result = statement.executeQuery()) {
                List<InventoryAggregate> aggregates = new ArrayList<>();
                while (result.next()) {
                    aggregates.add(readAggregate(result));
                }
                return List.copyOf(aggregates);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to aggregate inventory", exception);
        }
    }

    private static InventoryAggregate readAggregate(ResultSet result) throws SQLException {
        return new InventoryAggregate(
                result.getString("item_id"),
                InventoryItemKind.valueOf(result.getString("item_kind")),
                result.getLong("total_count"),
                result.getLong("fresh_count"),
                result.getLong("stale_count"),
                result.getLong("unknown_count")
        );
    }

    private static void rollback(Connection connection, Exception primary)
            throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

    private record ItemKey(String itemId, InventoryItemKind kind) {
    }
}
