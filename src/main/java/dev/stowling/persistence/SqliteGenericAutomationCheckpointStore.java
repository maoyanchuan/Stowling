package dev.stowling.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.automation.AutomationExecutionStatus;
import dev.stowling.automation.GenericAutomationCheckpoint;
import dev.stowling.automation.GenericAutomationCheckpointStore;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskKind;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stores only durable automation progress in the task recovery checkpoint.
 */
public final class SqliteGenericAutomationCheckpointStore
        implements GenericAutomationCheckpointStore {
    private static final int CHECKPOINT_VERSION = 1;
    private static final int MAX_MODULE_STATE_ENTRIES = 64;
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private static final Set<String> FORBIDDEN_STATE_MARKERS = Set.of(
            "nbt",
            "chat",
            "account",
            "token",
            "password",
            "authorization",
            "cookie"
    );

    private final SqliteTaskRepository tasks;

    public SqliteGenericAutomationCheckpointStore(SqliteTaskRepository tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public Optional<GenericAutomationCheckpoint> load(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        Optional<PersistedTask> record = tasks.findRecordById(taskId);
        if (record.isEmpty() || record.orElseThrow().snapshot().kind() != TaskKind.AUTOMATION) {
            return Optional.empty();
        }
        return record.orElseThrow().recoveryCheckpoint()
                .map(value -> parseCheckpoint(taskId, value));
    }

    @Override
    public void save(GenericAutomationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        validateStableState(checkpoint);
        PersistedTask current = tasks.findRecordById(checkpoint.taskId())
                .orElseThrow(() -> new IllegalArgumentException(
                "automation task does not exist: " + checkpoint.taskId()
        ));
        if (current.snapshot().kind() != TaskKind.AUTOMATION) {
            throw new IllegalArgumentException("checkpoint task is not an automation task");
        }
        TaskSnapshot existing = current.snapshot();
        Instant now = Instant.now();
        Instant updatedAt = now.isBefore(existing.updatedAt())
                ? existing.updatedAt()
                : now;
        TaskSnapshot updated = new TaskSnapshot(
                existing.id(),
                existing.kind(),
                statusOf(checkpoint.status()),
                automationStateOf(checkpoint.status()),
                existing.createdAt(),
                updatedAt,
                existing.failureReason()
        );
        tasks.save(new PersistedTask(
                updated,
                Optional.of(writeCheckpoint(checkpoint)),
                current.retryCount()
        ));
    }

    @Override
    public void delete(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        tasks.findRecordById(taskId).ifPresent(current -> tasks.save(new PersistedTask(
                current.snapshot(),
                Optional.empty(),
                current.retryCount()
        )));
    }

    public static TaskStatus statusOf(AutomationExecutionStatus status) {
        return switch (status) {
            case READY -> TaskStatus.PENDING;
            case RUNNING -> TaskStatus.RUNNING;
            case PAUSED -> TaskStatus.PAUSED;
            case SUCCEEDED -> TaskStatus.SUCCEEDED;
            case FAILED -> TaskStatus.FAILED;
        };
    }

    public static AutomationState automationStateOf(AutomationExecutionStatus status) {
        return switch (status) {
            case READY, RUNNING, PAUSED -> AutomationState.IDLE_WAITING;
            case SUCCEEDED -> AutomationState.COMPLETED;
            case FAILED -> AutomationState.FAILED;
        };
    }

    private static String writeCheckpoint(GenericAutomationCheckpoint checkpoint) {
        JsonObject root = new JsonObject();
        root.addProperty("version", CHECKPOINT_VERSION);
        root.addProperty("taskId", checkpoint.taskId().toString());
        root.addProperty("workflowId", checkpoint.workflowId());
        root.addProperty("stepIndex", checkpoint.stepIndex());
        root.addProperty("status", checkpoint.status().name());
        JsonObject moduleState = new JsonObject();
        checkpoint.moduleState().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> moduleState.addProperty(entry.getKey(), entry.getValue()));
        root.add("moduleState", moduleState);
        checkpoint.lastConfirmedAction().ifPresent(value ->
                root.addProperty("lastConfirmedAction", value)
        );
        return GSON.toJson(root);
    }

    private static GenericAutomationCheckpoint parseCheckpoint(UUID taskId, String value) {
        JsonElement parsed = JsonParser.parseString(value);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("automation checkpoint must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int version = requiredInt(root, "version");
        if (version != CHECKPOINT_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported automation checkpoint version: " + version
            );
        }
        UUID storedTaskId = UUID.fromString(requiredString(root, "taskId"));
        if (!taskId.equals(storedTaskId)) {
            throw new IllegalArgumentException(
                    "automation checkpoint task id does not match record"
            );
        }
        return new GenericAutomationCheckpoint(
                taskId,
                requiredString(root, "workflowId"),
                requiredInt(root, "stepIndex"),
                readModuleState(root.get("moduleState")),
                optionalString(root, "lastConfirmedAction"),
                AutomationExecutionStatus.valueOf(requiredString(root, "status"))
        );
    }

    private static Map<String, String> readModuleState(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("moduleState must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("moduleState values must be strings");
            }
            result.put(entry.getKey(), value.getAsString());
        }
        return Map.copyOf(result);
    }

    private static void validateStableState(GenericAutomationCheckpoint checkpoint) {
        if (checkpoint.moduleState().size() > MAX_MODULE_STATE_ENTRIES) {
            throw new IllegalArgumentException("moduleState has too many entries");
        }
        for (Map.Entry<String, String> entry : checkpoint.moduleState().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_STATE_MARKERS.stream()
                    .anyMatch(marker -> key.contains(marker) || value.contains(marker))) {
                throw new IllegalArgumentException(
                        "moduleState contains a non-durable field"
                );
            }
        }
    }

    private static String requiredString(JsonObject root, String fieldName) {
        JsonElement value = root.get(fieldName);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return value.getAsString();
    }

    private static Optional<String> optionalString(JsonObject root, String fieldName) {
        JsonElement value = root.get(fieldName);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return Optional.of(value.getAsString());
    }

    private static int requiredInt(JsonObject root, String fieldName) {
        JsonElement value = root.get(fieldName);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(fieldName + " must be an integer");
        }
        return value.getAsInt();
    }
}
