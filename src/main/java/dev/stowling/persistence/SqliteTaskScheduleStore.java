package dev.stowling.persistence;

import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskScheduleRecord;
import dev.stowling.schedule.TaskScheduleStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SqliteTaskScheduleStore implements TaskScheduleStore {
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS task_schedules (
                key_kind TEXT NOT NULL,
                key_id TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                state TEXT NOT NULL,
                priority TEXT NOT NULL,
                next_run_at INTEGER,
                last_finished_at INTEGER,
                run_id TEXT,
                run_task_kind TEXT,
                run_task_id TEXT,
                queued INTEGER NOT NULL,
                queued_run_id TEXT,
                queue_order INTEGER NOT NULL,
                rerun_pending INTEGER NOT NULL,
                message TEXT NOT NULL,
                unsafe_locked INTEGER NOT NULL,
                last_probe_at INTEGER,
                PRIMARY KEY (key_kind, key_id)
            )
            """;
    private static final String UPSERT_SQL = """
            INSERT INTO task_schedules(
                key_kind,
                key_id,
                enabled,
                state,
                priority,
                next_run_at,
                last_finished_at,
                run_id,
                run_task_kind,
                run_task_id,
                queued,
                queued_run_id,
                queue_order,
                rerun_pending,
                message,
                unsafe_locked,
                last_probe_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(key_kind, key_id) DO UPDATE SET
                enabled = excluded.enabled,
                state = excluded.state,
                priority = excluded.priority,
                next_run_at = excluded.next_run_at,
                last_finished_at = excluded.last_finished_at,
                run_id = excluded.run_id,
                run_task_kind = excluded.run_task_kind,
                run_task_id = excluded.run_task_id,
                queued = excluded.queued,
                queued_run_id = excluded.queued_run_id,
                queue_order = excluded.queue_order,
                rerun_pending = excluded.rerun_pending,
                message = excluded.message,
                unsafe_locked = excluded.unsafe_locked,
                last_probe_at = excluded.last_probe_at
            """;
    private static final String SELECT_SQL = """
            SELECT
                key_kind,
                key_id,
                enabled,
                state,
                priority,
                next_run_at,
                last_finished_at,
                run_id,
                run_task_kind,
                run_task_id,
                queued,
                queued_run_id,
                queue_order,
                rerun_pending,
                message,
                unsafe_locked,
                last_probe_at
            FROM task_schedules
            ORDER BY key_kind, key_id
            """;

    private final StowlingDatabase database;

    public SqliteTaskScheduleStore(StowlingDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        createTable();
    }

    @Override
    public List<TaskScheduleRecord> load() {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_SQL);
                ResultSet result = statement.executeQuery()) {
            List<TaskScheduleRecord> records = new ArrayList<>();
            while (result.next()) {
                records.add(readRecord(result));
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new PersistenceException("failed to load task schedules", exception);
        }
    }

    @Override
    public void save(TaskScheduleRecord record) {
        Objects.requireNonNull(record, "record");
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            statement.setString(1, record.key().kind());
            statement.setString(2, record.key().id());
            statement.setInt(3, booleanInt(record.enabled()));
            statement.setString(4, record.state());
            statement.setString(5, record.priority().name());
            setNullableInstant(statement, 6, record.nextRunAt());
            setNullableInstant(statement, 7, record.lastFinishedAt());
            setNullableUuid(statement, 8, record.runId());
            setNullableKey(statement, 9, 10, record.runTaskKey());
            statement.setInt(11, booleanInt(record.queued()));
            setNullableUuid(statement, 12, record.queuedRunId());
            statement.setLong(13, record.queueOrder());
            statement.setInt(14, booleanInt(record.rerunPending()));
            statement.setString(15, record.message());
            statement.setInt(16, booleanInt(record.unsafeLocked()));
            setNullableInstant(statement, 17, record.lastProbeAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new PersistenceException("failed to save task schedule", exception);
        }
    }

    private void createTable() {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(CREATE_TABLE_SQL)) {
            statement.execute();
        } catch (SQLException exception) {
            throw new PersistenceException("failed to initialize task schedules", exception);
        }
    }

    private static TaskScheduleRecord readRecord(ResultSet result) throws SQLException {
        TaskKey key = new TaskKey(result.getString("key_kind"), result.getString("key_id"));
        String runTaskKind = result.getString("run_task_kind");
        String runTaskId = result.getString("run_task_id");
        TaskKey runTaskKey = runTaskKind == null || runTaskId == null
                ? null
                : new TaskKey(runTaskKind, runTaskId);
        return new TaskScheduleRecord(
                key,
                result.getInt("enabled") != 0,
                result.getString("state"),
                TaskPriority.valueOf(result.getString("priority")),
                readInstant(result, "next_run_at"),
                readInstant(result, "last_finished_at"),
                readUuid(result, "run_id"),
                runTaskKey,
                result.getInt("queued") != 0,
                readUuid(result, "queued_run_id"),
                result.getLong("queue_order"),
                result.getInt("rerun_pending") != 0,
                result.getString("message"),
                result.getInt("unsafe_locked") != 0,
                readInstant(result, "last_probe_at")
        );
    }

    private static void setNullableInstant(
            PreparedStatement statement,
            int index,
            Instant value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private static void setNullableUuid(
            PreparedStatement statement,
            int index,
            UUID value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private static void setNullableKey(
            PreparedStatement statement,
            int kindIndex,
            int idIndex,
            TaskKey value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(kindIndex, Types.VARCHAR);
            statement.setNull(idIndex, Types.VARCHAR);
        } else {
            statement.setString(kindIndex, value.kind());
            statement.setString(idIndex, value.id());
        }
    }

    private static Instant readInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static UUID readUuid(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static int booleanInt(boolean value) {
        return value ? 1 : 0;
    }
}
