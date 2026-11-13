package dev.stowling.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.automation.AutomationCheckpoint;
import dev.stowling.automation.AutomationCheckpointStore;
import dev.stowling.automation.PickupStage;
import dev.stowling.task.AutomationState;
import dev.stowling.task.TaskSnapshot;
import dev.stowling.task.TaskStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists only the resumable pickup stage, never slot-level attempts or clicks.
 */
public final class SqliteAutomationCheckpointStore implements AutomationCheckpointStore {
    private static final int CHECKPOINT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final SqliteTaskRepository tasks;

    public SqliteAutomationCheckpointStore(SqliteTaskRepository tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public Optional<AutomationCheckpoint> load(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return tasks.findRecordById(taskId)
                .flatMap(PersistedTask::recoveryCheckpoint)
                .map(value -> parseCheckpoint(taskId, value));
    }

    public static Optional<String> readPlanReference(UUID taskId, String value) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(value, "value");
        return parseCheckpoint(taskId, value).planReference();
    }

    @Override
    public void save(AutomationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        TaskSnapshot current = tasks.findById(checkpoint.taskId()).orElseThrow();
        TaskStatus status = statusOf(checkpoint.stage());
        AutomationState state = automationStateOf(checkpoint.stage());
        TaskSnapshot updated = new TaskSnapshot(
                current.id(),
                current.kind(),
                status,
                state,
                current.createdAt(),
                current.updatedAt().isAfter(java.time.Instant.now())
                ? current.updatedAt()
                : java.time.Instant.now(),
                current.failureReason()
        );
        tasks.save(new PersistedTask(
                updated,
                Optional.of(writeCheckpoint(checkpoint)),
                0
        ));
    }

    @Override
    public void delete(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
    }

    public static TaskStatus statusOf(PickupStage stage) {
        return switch (stage) {
            case NAVIGATE_PICKUP, COLLECT, DEPOSIT_BUFFER, CLASSIFY_BUFFER,
                    DELIVER_LOOSE, PACK -> TaskStatus.RUNNING;
            case WAIT_FOR_IDLE -> TaskStatus.WAITING;
            case COMPLETE -> TaskStatus.SUCCEEDED;
            case FAILED -> TaskStatus.FAILED;
            case PAUSED -> TaskStatus.PAUSED;
        };
    }

    public static AutomationState automationStateOf(PickupStage stage) {
        return switch (stage) {
            case NAVIGATE_PICKUP, COLLECT -> AutomationState.PICKING_UP;
            case DEPOSIT_BUFFER -> AutomationState.CACHING;
            case WAIT_FOR_IDLE -> AutomationState.IDLE_WAITING;
            case CLASSIFY_BUFFER, DELIVER_LOOSE -> AutomationState.SORTING;
            case PACK -> AutomationState.PACKING;
            case COMPLETE -> AutomationState.COMPLETED;
            case PAUSED, FAILED -> AutomationState.FAILED;
        };
    }

    private static String writeCheckpoint(AutomationCheckpoint checkpoint) {
        JsonObject root = new JsonObject();
        root.addProperty("version", CHECKPOINT_VERSION);
        root.addProperty("taskId", checkpoint.taskId().toString());
        root.addProperty("stage", checkpoint.stage().name());
        checkpoint.planReference().ifPresent(value ->
                root.addProperty("planReference", value)
        );
        JsonArray observedInputs = new JsonArray();
        checkpoint.observedInputKeys().stream().sorted().forEach(observedInputs::add);
        root.add("observedInputKeys", observedInputs);
        checkpoint.idleSince().ifPresent(value ->
                root.addProperty("idleSince", value.toString())
        );
        JsonObject inventoryBaseline = new JsonObject();
        checkpoint.inventoryBaseline().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> inventoryBaseline.addProperty(
                entry.getKey(),
                entry.getValue()
        ));
        root.add("inventoryBaseline", inventoryBaseline);
        checkpoint.selectedCacheId().ifPresent(value ->
                root.addProperty("selectedCacheId", value)
        );
        root.addProperty(
                "classificationCacheIndex",
                checkpoint.classificationCacheIndex()
        );
        return GSON.toJson(root);
    }

    private static AutomationCheckpoint parseCheckpoint(UUID taskId, String value) {
        if (value.stripLeading().startsWith("{")) {
            return readJsonCheckpoint(taskId, value);
        }
        return readLegacyCheckpoint(taskId, value);
    }

    private static AutomationCheckpoint readJsonCheckpoint(UUID taskId, String value) {
        JsonElement parsed = JsonParser.parseString(value);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("automation checkpoint must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int version = root.get("version").getAsInt();
        if (version != CHECKPOINT_VERSION) {
            throw new IllegalArgumentException("unsupported checkpoint version: " + version);
        }
        UUID storedTaskId = UUID.fromString(root.get("taskId").getAsString());
        if (!taskId.equals(storedTaskId)) {
            throw new IllegalArgumentException("checkpoint task id does not match task record");
        }
        PickupStage stage = PickupStage.valueOf(root.get("stage").getAsString());
        Optional<String> planReference = optionalString(root, "planReference");
        Optional<Instant> idleSince = optionalString(root, "idleSince")
                .map(Instant::parse);
        Optional<String> selectedCacheId = optionalString(root, "selectedCacheId");
        int classificationCacheIndex = root.get("classificationCacheIndex").getAsInt();
        return new AutomationCheckpoint(
                taskId,
                stage,
                planReference,
                readStringSet(root.get("observedInputKeys")),
                idleSince,
                readInventoryBaseline(root.get("inventoryBaseline")),
                selectedCacheId,
                classificationCacheIndex
        );
    }

    private static AutomationCheckpoint readLegacyCheckpoint(UUID taskId, String value) {
        int separator = value.lastIndexOf('|');
        if (separator < 0) {
            return legacyCheckpoint(taskId, Optional.empty(), value);
        }
        String siteId = value.substring(0, separator);
        String stage = value.substring(separator + 1);
        Optional<String> planReference = siteId.isEmpty()
                ? Optional.empty()
                : Optional.of(siteId);
        return legacyCheckpoint(taskId, planReference, stage);
    }

    private static AutomationCheckpoint legacyCheckpoint(
            UUID taskId,
            Optional<String> planReference,
            String stage
    ) {
        PickupStage parsedStage = PickupStage.valueOf(stage);
        if (parsedStage == PickupStage.PAUSED || parsedStage == PickupStage.FAILED) {
            throw new IllegalArgumentException("legacy checkpoint has no resumable stage");
        }
        return new AutomationCheckpoint(
                taskId,
                parsedStage,
                planReference,
                Set.of(),
                Optional.empty(),
                Map.of()
        );
    }

    private static Optional<String> optionalString(JsonObject root, String fieldName) {
        JsonElement value = root.get(fieldName);
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString());
    }

    private static Set<String> readStringSet(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("observedInputKeys must be an array");
        }
        JsonArray values = element.getAsJsonArray();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            result.add(value.getAsString());
        }
        return Set.copyOf(result);
    }

    private static Map<String, Integer> readInventoryBaseline(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("inventoryBaseline must be an object");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAsInt());
        }
        return Map.copyOf(result);
    }
}
