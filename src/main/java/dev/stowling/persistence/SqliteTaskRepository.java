package dev.stowling.persistence;

import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskRepository;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SqliteTaskRepository implements TaskRepository {
    private static final String UPSERT_SQL = """
            INSERT INTO tasks(
                task_id,
                task_kind,
                task_status,
                automation_state,
                created_at,
                updated_at,
                failure_reason,
                recovery_checkpoint,
                retry_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                task_kind = excluded.task_kind,
                task_status = excluded.task_status,
                automation_state = excluded.automation_state,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                failure_reason = excluded.failure_reason,
                recovery_checkpoint = excluded.recovery_checkpoint,
                retry_count = excluded.retry_count
            """;

    private final StowlingDatabase database;

    public SqliteTaskRepository(StowlingDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        database.ensureMigrated();
    }

    @Override
    public void save(TaskSnapshot task) {
        save(PersistedTask.fromSnapshot(task));
    }

    public void save(PersistedTask task) {
        Objects.requireNonNull(task, "task");
        TaskSnapshot snapshot = task.snapshot();
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            statement.setString(1, snapshot.id().toString());
            statement.setString(2, snapshot.kind().name());
            statement.setString(3, snapshot.status().name());
            statement.setString(4, snapshot.automationState().name());
            statement.setLong(5, snapshot.createdAt().toEpochMilli());
            statement.setLong(6, snapshot.updatedAt().toEpochMilli());
            setOptionalText(statement, 7, snapshot.failureReason());
            setOptionalText(statement, 8, task.recoveryCheckpoint());
            statement.setInt(9, task.retryCount());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new PersistenceException("failed to save task", exception);
        }
    }

    @Override
    public Optional<TaskSnapshot> findById(UUID id) {
        return findRecordById(id).map(PersistedTask::snapshot);
    }

    public Optional<PersistedTask> findRecordById(UUID id) {
        Objects.requireNonNull(id, "id");
        String sql = selectSql() + " WHERE task_id = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTask(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to find task", exception);
        }
    }

    @Override
    public List<TaskSnapshot> findAll() {
        return findAllRecords().stream().map(PersistedTask::snapshot).toList();
    }

    public List<PersistedTask> findAllRecords() {
        String sql = selectSql() + " ORDER BY created_at, task_id";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            return readTasks(result);
        } catch (SQLException exception) {
            throw new PersistenceException("failed to list tasks", exception);
        }
    }

    public List<TaskRecoveryPlan> findRecoveryPlans() {
        String sql = selectSql() + """
                 WHERE task_status IN (?, ?, ?, ?)
                 ORDER BY updated_at, task_id
                """;
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TaskStatus.PENDING.name());
            statement.setString(2, TaskStatus.RUNNING.name());
            statement.setString(3, TaskStatus.WAITING.name());
            statement.setString(4, TaskStatus.PAUSED.name());
            try (ResultSet result = statement.executeQuery()) {
                List<TaskRecoveryPlan> plans = new ArrayList<>();
                while (result.next()) {
                    plans.add(TaskRecoveryPlan.from(readTask(result)));
                }
                return List.copyOf(plans);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("failed to list recoverable tasks", exception);
        }
    }

    public void delete(UUID id) {
        Objects.requireNonNull(id, "id");
        String sql = "DELETE FROM tasks WHERE task_id = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new PersistenceException("failed to delete task", exception);
        }
    }

    private static String selectSql() {
        return """
                SELECT
                    task_id,
                    task_kind,
                    task_status,
                    automation_state,
                    created_at,
                    updated_at,
                    failure_reason,
                    recovery_checkpoint,
                    retry_count
                FROM tasks
                """;
    }

    private static List<PersistedTask> readTasks(ResultSet result) throws SQLException {
        List<PersistedTask> tasks = new ArrayList<>();
        while (result.next()) {
            tasks.add(readTask(result));
        }
        return List.copyOf(tasks);
    }

    private static PersistedTask readTask(ResultSet result) throws SQLException {
        TaskSnapshot snapshot = new TaskSnapshot(
                UUID.fromString(result.getString("task_id")),
                TaskKind.valueOf(result.getString("task_kind")),
                TaskStatus.valueOf(result.getString("task_status")),
                AutomationState.valueOf(result.getString("automation_state")),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")),
                Optional.ofNullable(result.getString("failure_reason"))
        );
        return new PersistedTask(
                snapshot,
                Optional.ofNullable(result.getString("recovery_checkpoint")),
                result.getInt("retry_count")
        );
    }

    private static void setOptionalText(
            PreparedStatement statement,
            int index,
            Optional<String> value
    ) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
