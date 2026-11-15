package dev.stowling.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class StowlingDatabase {
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final String CONTAINERS_SQL = """
            CREATE TABLE containers (
                container_id TEXT PRIMARY KEY,
                dimension TEXT NOT NULL,
                block_x INTEGER NOT NULL,
                block_y INTEGER NOT NULL,
                block_z INTEGER NOT NULL,
                container_type TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;
    private static final String SNAPSHOTS_SQL = """
            CREATE TABLE inventory_snapshots (
                container_id TEXT PRIMARY KEY
                    REFERENCES containers(container_id) ON DELETE CASCADE,
                source_id TEXT NOT NULL,
                dimension TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                observed_at INTEGER NOT NULL,
                freshness TEXT NOT NULL
            )
            """;
    private static final String SCOPES_SQL = """
            CREATE TABLE inventory_scopes (
                container_id TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                parent_scope_id TEXT,
                PRIMARY KEY (container_id, scope_id),
                FOREIGN KEY (container_id)
                    REFERENCES inventory_snapshots(container_id) ON DELETE CASCADE,
                FOREIGN KEY (container_id, parent_scope_id)
                    REFERENCES inventory_scopes(container_id, scope_id)
                    ON DELETE CASCADE
            )
            """;
    private static final String ENTRIES_SQL = """
            CREATE TABLE inventory_entries (
                container_id TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                item_kind TEXT NOT NULL,
                item_count INTEGER NOT NULL CHECK (item_count >= 0),
                PRIMARY KEY (container_id, scope_id, item_id, item_kind),
                FOREIGN KEY (container_id, scope_id)
                    REFERENCES inventory_scopes(container_id, scope_id)
                    ON DELETE CASCADE
            )
            """;
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, migrationStatements()),
            new Migration(2, List.of(
                    "DROP TABLE IF EXISTS task_run_definitions",
                    "DROP TABLE IF EXISTS task_schedules",
                    "DROP TABLE IF EXISTS tasks"
            ))
    );

    private final Path databasePath;
    private volatile boolean migrated;

    public StowlingDatabase(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath()
                .normalize();
    }

    public synchronized void ensureMigrated() {
        if (migrated) {
            return;
        }
        migrate();
    }

    public synchronized void migrate() {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                createMigrationTable(connection);
                applyPendingMigrations(connection);
                connection.commit();
                migrated = true;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to initialize SQLite database", exception);
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(JDBC_PREFIX + databasePath);
        try {
            configureConnection(connection);
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    public int currentSchemaVersion() {
        ensureMigrated();
        String sql = "SELECT COALESCE(MAX(version), 0) FROM schema_migrations";
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new PersistenceException("failed to read schema version", exception);
        }
    }

    private static List<String> migrationStatements() {
        return List.of(
                CONTAINERS_SQL,
                SNAPSHOTS_SQL,
                SCOPES_SQL,
                ENTRIES_SQL
        );
    }

    private static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        }
    }

    private static void createMigrationTable(Connection connection) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at INTEGER NOT NULL
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void applyPendingMigrations(Connection connection) throws SQLException {
        for (Migration migration : MIGRATIONS) {
            if (!isApplied(connection, migration.version())) {
                applyMigration(connection, migration);
            }
        }
    }

    private static boolean isApplied(Connection connection, int version) throws SQLException {
        String sql = "SELECT 1 FROM schema_migrations WHERE version = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void applyMigration(Connection connection, Migration migration)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : migration.statements()) {
                statement.execute(sql);
            }
        }
        String insertSql = "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setInt(1, migration.version());
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Exception primary)
            throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

    private record Migration(int version, List<String> statements) {
        private Migration {
            statements = List.copyOf(statements);
        }
    }
}
