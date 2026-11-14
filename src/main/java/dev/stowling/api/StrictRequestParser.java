package dev.stowling.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.stowling.control.AuditRequest;
import dev.stowling.control.AutomationTaskRequest;
import dev.stowling.control.AutomationWorkflowUpdateRequest;
import dev.stowling.control.ConfigCoordinateUpdate;
import dev.stowling.control.ConfigCoordinatesUpdateRequest;
import dev.stowling.control.ConfigCoordinatePointInfo;
import dev.stowling.control.ConfigCoordinatePointsUpdateRequest;
import dev.stowling.control.ContainerPosition;
import dev.stowling.control.PickupConfigUpdateRequest;
import dev.stowling.control.PickupRequest;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.automation.CustomPickupRules;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TaskSettings;
import dev.stowling.schedule.TaskTrigger;
import dev.stowling.schedule.TriggerKind;
import dev.stowling.schedule.VersionedTaskDefinition;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class StrictRequestParser {
    private static final Pattern SAFE_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}"
    );
    private static final Set<String> PICKUP_REGION_FIELDS = Set.of(
            "id",
            "name",
            "dimension",
            "standbyCoordinateId",
            "standbyPosition",
            "shape"
    );
    private static final Set<String> NAMED_CACHE_FIELDS = Set.of(
            "id",
            "name",
            "coordinateId",
            "dimension",
            "position",
            "type"
    );
    private static final Set<String> SPHERE_SHAPE_FIELDS = Set.of(
            "type",
            "centerCoordinateId",
            "center",
            "radius"
    );
    private static final Set<String> CUBOID_SHAPE_FIELDS = Set.of(
            "type",
            "firstCornerCoordinateId",
            "secondCornerCoordinateId",
            "firstCorner",
            "secondCorner"
    );
    private static final Set<String> TASK_DEFINITION_FIELDS = Set.of(
            "kind",
            "id",
            "name",
            "settings",
            "pickup",
            "steps"
    );
    private static final Set<String> TASK_SETTINGS_FIELDS = Set.of(
            "priority",
            "trigger",
            "autoStartOnRestart",
            "interruption"
    );
    private static final Set<String> TASK_TRIGGER_FIELDS = Set.of(
            "kind",
            "intervalSeconds",
            "times",
            "zoneId",
            "regionIds"
    );
    private static final Set<String> TASK_INTERRUPTION_FIELDS = Set.of(
            "kind",
            "steps",
            "taskId"
    );
    private static final Set<String> PICKUP_WORKFLOW_FIELDS = Set.of(
            "id",
            "name",
            "dimension",
            "regions",
            "cacheIds",
            "cachePolicy",
            "classificationTiming",
            "completionMode",
            "completionIdleDelay",
            "settings"
    );
    private static final Set<String> PICKUP_WORKFLOW_REQUIRED_FIELDS = Set.of(
            "id",
            "name",
            "dimension",
            "regions",
            "cacheIds",
            "cachePolicy",
            "classificationTiming",
            "completionMode",
            "completionIdleDelay"
    );
    private static final Set<String> AUTOMATION_WORKFLOW_FIELDS = Set.of(
            "id",
            "name",
            "steps",
            "settings"
    );
    private static final Set<String> AUTOMATION_WORKFLOW_REQUIRED_FIELDS = Set.of(
            "id",
            "name",
            "steps"
    );

    TaskDefinition parseTaskDefinition(String body) throws InvalidRequestException {
        try {
            return taskDefinition(readStrictObject(body));
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    VersionedTaskDefinition parseVersionedTaskDefinition(String body)
            throws InvalidRequestException {
        try {
            JsonObject root = readStrictObject(body);
            requireFields(
                    root,
                    Set.of("revision", "definition"),
                    Set.of("revision", "definition")
            );
            return new VersionedTaskDefinition(
                    string(root, "revision", 128),
                    taskDefinition(object(root.get("definition"), "definition"))
            );
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    String parseTaskDefinitionRevision(String body) throws InvalidRequestException {
        try {
            JsonObject root = readStrictObject(body);
            requireFields(root, Set.of("revision"), Set.of("revision"));
            return string(root, "revision", 128);
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    TaskKey parseTaskKey(String kind, String id) throws InvalidRequestException {
        if (kind == null
                || (!TaskKey.PICKUP.equals(kind) && !TaskKey.AUTOMATION.equals(kind))) {
            throw new InvalidRequestException("Task kind must be PICKUP or AUTOMATION");
        }
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new InvalidRequestException(
                    "Task id must match [a-z0-9][a-z0-9._-]{0,63}"
            );
        }
        try {
            return new TaskKey(kind, id);
        } catch (IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    PickupRequest parsePickup(String body) throws InvalidRequestException {
        try (JsonReader reader = reader(body)) {
            Set<String> fields = new HashSet<>();
            String workflowId = null;
            String regionId = null;
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "JSON body must be an object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                require(fields.add(name), "Duplicate JSON field: " + name);
                if ("workflowId".equals(name)) {
                    workflowId = readString(reader, "workflowId");
                } else if ("regionId".equals(name)) {
                    regionId = readString(reader, "regionId");
                } else {
                    throw new IOException("Unknown JSON field: " + name);
                }
            }
            reader.endObject();
            requireEnd(reader);
            require(workflowId != null, "Missing JSON field: workflowId");
            return new PickupRequest(workflowId, java.util.Optional.ofNullable(regionId));
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    AutomationTaskRequest parseAutomationTask(String body) throws InvalidRequestException {
        try (JsonReader reader = reader(body)) {
            Set<String> fields = new HashSet<>();
            String workflowId = null;
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "JSON body must be an object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                require(fields.add(name), "Duplicate JSON field: " + name);
                if ("workflowId".equals(name)) {
                    workflowId = readString(reader, "workflowId");
                    require(workflowId.length() <= 64, "workflowId is too long");
                    require(
                            SAFE_ID.matcher(workflowId).matches(),
                            "workflowId must match [a-z0-9][a-z0-9._-]{0,63}"
                    );
                } else {
                    throw new IOException("Unknown JSON field: " + name);
                }
            }
            reader.endObject();
            requireEnd(reader);
            require(workflowId != null, "Missing JSON field: workflowId");
            return new AutomationTaskRequest(workflowId);
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    AutomationWorkflowUpdateRequest parseAutomationWorkflowUpdate(String body)
            throws InvalidRequestException {
        try {
            JsonObject root = readStrictObject(body);
            requireFields(
                    root,
                    Set.of("revision", "automationWorkflows"),
                    Set.of("revision", "automationWorkflows")
            );
            return new AutomationWorkflowUpdateRequest(
                    string(root, "revision", 128),
                    automationWorkflows(array(root, "automationWorkflows"))
            );
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    PickupConfigUpdateRequest parsePickupConfigUpdate(String body)
            throws InvalidRequestException {
        try {
            return pickupConfigUpdate(readStrictObject(body));
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    PickupConfigUpdateRequest parseTaskResourcesUpdate(String body)
            throws InvalidRequestException {
        try {
            JsonObject root = readStrictObject(body);
            requireFields(
                    root,
                    Set.of(
                            "revision",
                            "pickupRegions",
                            "cacheContainers"
                    ),
                    Set.of(
                            "revision",
                            "pickupRegions",
                            "cacheContainers"
                    )
            );
            root.add("pickupWorkflows", new JsonArray());
            return pickupConfigUpdate(root);
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    private static PickupConfigUpdateRequest pickupConfigUpdate(JsonObject root)
            throws IOException {
        requireFields(
                root,
                Set.of(
                        "revision",
                        "pickupRegions",
                        "cacheContainers",
                        "pickupWorkflows"
                ),
                Set.of(
                        "revision",
                        "pickupRegions",
                        "cacheContainers",
                        "pickupWorkflows"
                )
        );
        return new PickupConfigUpdateRequest(
                string(root, "revision", 128),
                pickupRegions(array(root, "pickupRegions")),
                namedCaches(array(root, "cacheContainers")),
                pickupWorkflows(array(root, "pickupWorkflows"))
        );
    }

    AuditRequest parseAudit(String body) throws InvalidRequestException {
        try (JsonReader reader = reader(body)) {
            Set<String> fields = new HashSet<>();
            List<String> containerIds = List.of();
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "JSON body must be an object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                require(fields.add(name), "Duplicate JSON field: " + name);
                require("containerIds".equals(name), "Unknown JSON field: " + name);
                containerIds = readStringArray(reader, "containerIds");
            }
            reader.endObject();
            requireEnd(reader);
            return new AuditRequest(containerIds);
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    ConfigCoordinatePointsUpdateRequest parseCoordinatePointsUpdate(String body)
            throws InvalidRequestException {
        try {
            JsonObject root = readStrictObject(body);
            requireFields(
                    root,
                    Set.of("revision", "coordinatePoints"),
                    Set.of("revision", "coordinatePoints")
            );
            return new ConfigCoordinatePointsUpdateRequest(
                    string(root, "revision", 128),
                    coordinatePointInfos(array(root, "coordinatePoints"))
            );
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    ConfigCoordinatesUpdateRequest parseCoordinateUpdate(String body)
            throws InvalidRequestException {
        try (JsonReader reader = reader(body)) {
            Set<String> fields = new HashSet<>();
            String revision = null;
            List<ConfigCoordinateUpdate> coordinates = null;
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "JSON body must be an object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                require(fields.add(name), "Duplicate JSON field: " + name);
                if ("revision".equals(name)) {
                    revision = readString(reader, "revision");
                } else if ("coordinates".equals(name)) {
                    coordinates = readCoordinates(reader);
                } else {
                    throw new IOException("Unknown JSON field: " + name);
                }
            }
            reader.endObject();
            requireEnd(reader);
            require(revision != null && !revision.isBlank(), "Missing JSON field: revision");
            require(coordinates != null, "Missing JSON field: coordinates");
            return new ConfigCoordinatesUpdateRequest(revision, coordinates);
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
    }

    void requireEmpty(String body) throws InvalidRequestException {
        if (!body.isBlank()) {
            throw new InvalidRequestException("This endpoint does not accept a request body");
        }
    }

    private static JsonReader reader(String body) {
        JsonReader reader = new JsonReader(new StringReader(body));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    private static String readString(JsonReader reader, String fieldName) throws IOException {
        require(reader.peek() == JsonToken.STRING, fieldName + " must be a string");
        return reader.nextString();
    }

    private static List<String> readStringArray(JsonReader reader, String fieldName)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, fieldName + " must be an array");
        List<String> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            values.add(readString(reader, fieldName));
        }
        reader.endArray();
        return List.copyOf(values);
    }

    private static List<ConfigCoordinateUpdate> readCoordinates(JsonReader reader)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "coordinates must be an array");
        List<ConfigCoordinateUpdate> coordinates = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            coordinates.add(readCoordinate(reader));
        }
        reader.endArray();
        return List.copyOf(coordinates);
    }

    private static ConfigCoordinateUpdate readCoordinate(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "coordinate must be an object");
        Set<String> fields = new HashSet<>();
        String key = null;
        Integer x = null;
        Integer y = null;
        Integer z = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            require(fields.add(name), "Duplicate JSON field: " + name);
            switch (name) {
                case "key" -> key = readString(reader, "key");
                case "x" -> x = readInteger(reader, "x");
                case "y" -> y = readInteger(reader, "y");
                case "z" -> z = readInteger(reader, "z");
                default -> throw new IOException("Unknown JSON field: " + name);
            }
        }
        reader.endObject();
        require(key != null, "Missing JSON field: key");
        require(x != null, "Missing JSON field: x");
        require(y != null, "Missing JSON field: y");
        require(z != null, "Missing JSON field: z");
        return new ConfigCoordinateUpdate(key, x, y, z);
    }

    private static int readInteger(JsonReader reader, String fieldName) throws IOException {
        require(reader.peek() == JsonToken.NUMBER, fieldName + " must be an integer");
        String value = reader.nextString();
        require(value.matches("-?(0|[1-9][0-9]*)"), fieldName + " must be an integer");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException(fieldName + " is outside the integer range", exception);
        }
    }

    private static JsonObject readStrictObject(String body) throws IOException {
        try (JsonReader reader = reader(body)) {
            JsonElement element = readStrictElement(reader);
            requireEnd(reader);
            require(element.isJsonObject(), "JSON body must be an object");
            return element.getAsJsonObject();
        }
    }

    private static JsonElement readStrictElement(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readStrictJsonObject(reader);
            case BEGIN_ARRAY -> readStrictJsonArray(reader);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Malformed JSON body");
        };
    }

    private static JsonObject readStrictJsonObject(JsonReader reader) throws IOException {
        JsonObject result = new JsonObject();
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            require(fields.add(name), "Duplicate JSON field: " + name);
            result.add(name, readStrictElement(reader));
        }
        reader.endObject();
        return result;
    }

    private static JsonArray readStrictJsonArray(JsonReader reader) throws IOException {
        JsonArray result = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) {
            result.add(readStrictElement(reader));
        }
        reader.endArray();
        return result;
    }

    private static TaskDefinition taskDefinition(JsonObject value) throws IOException {
        requireFields(value, TASK_DEFINITION_FIELDS, TASK_DEFINITION_FIELDS);
        String kind = taskKind(value);
        TaskSettings settings = taskSettings(
                object(value.get("settings"), "settings")
        );
        JsonElement pickupElement = value.get("pickup");
        PickupWorkflow pickup = pickupElement.isJsonNull()
                ? null
                : pickupWorkflow(
                        object(pickupElement, "pickup"),
                        "pickup",
                        settings
                );
        return new TaskDefinition(
                kind,
                string(value, "id", 64),
                string(value, "name", 256),
                settings,
                pickup,
                automationSteps(array(value, "steps"))
        );
    }

    private static String taskKind(JsonObject value) throws IOException {
        String kind = string(value, "kind", 32);
        require(
                TaskKey.PICKUP.equals(kind) || TaskKey.AUTOMATION.equals(kind),
                "kind must be PICKUP or AUTOMATION"
        );
        return kind;
    }

    private static TaskSettings taskSettings(JsonObject value) throws IOException {
        requireFields(value, TASK_SETTINGS_FIELDS, TASK_SETTINGS_FIELDS);
        return new TaskSettings(
                enumValue(TaskPriority.class, value, "priority"),
                taskTrigger(object(value.get("trigger"), "trigger")),
                booleanValue(value, "autoStartOnRestart"),
                taskInterruption(object(value.get("interruption"), "interruption"))
        );
    }

    private static TaskTrigger taskTrigger(JsonObject value) throws IOException {
        requireFields(value, TASK_TRIGGER_FIELDS, TASK_TRIGGER_FIELDS);
        return new TaskTrigger(
                enumValue(TriggerKind.class, value, "kind"),
                nonNegativeLong(value, "intervalSeconds"),
                strings(array(value, "times"), "times", 16),
                string(value, "zoneId", 128),
                strings(array(value, "regionIds"), "regionIds", 64)
        );
    }

    private static TaskInterruption taskInterruption(JsonObject value) throws IOException {
        requireFields(value, TASK_INTERRUPTION_FIELDS, TASK_INTERRUPTION_FIELDS);
        return new TaskInterruption(
                enumValue(InterruptionKind.class, value, "kind"),
                automationSteps(array(value, "steps")),
                nullableString(value, "taskId", 64)
        );
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> enumType,
            JsonObject value,
            String fieldName
    ) throws IOException {
        String text = string(value, fieldName, 64);
        try {
            return Enum.valueOf(enumType, text);
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    fieldName + " has an unsupported value: " + text,
                    exception
            );
        }
    }

    private static boolean booleanValue(JsonObject value, String fieldName)
            throws IOException {
        JsonElement element = value.get(fieldName);
        require(element != null && element.isJsonPrimitive(),
                fieldName + " must be a boolean");
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        require(primitive.isBoolean(), fieldName + " must be a boolean");
        return primitive.getAsBoolean();
    }

    private static String nullableString(
            JsonObject value,
            String fieldName,
            int maximumLength
    ) throws IOException {
        JsonElement element = value.get(fieldName);
        require(element != null, "Missing JSON field: " + fieldName);
        if (element.isJsonNull()) {
            return null;
        }
        return string(value, fieldName, maximumLength);
    }

    private static List<PickupRegion> pickupRegions(JsonArray values) throws IOException {
        List<PickupRegion> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(values.get(index), "pickupRegions[" + index + "]");
            requireFields(value, PICKUP_REGION_FIELDS, PICKUP_REGION_FIELDS);
            result.add(new PickupRegion(
                    string(value, "id", 64),
                    string(value, "name", 256),
                    string(value, "dimension", 128),
                    string(value, "standbyCoordinateId", 64),
                    position(object(value.get("standbyPosition"), "standbyPosition")),
                    shape(object(value.get("shape"), "shape"))
            ));
        }
        return List.copyOf(result);
    }

    private static List<AutomationWorkflow> automationWorkflows(JsonArray values)
            throws IOException {
        require(values.size() <= 128, "automationWorkflows has too many entries");
        List<AutomationWorkflow> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String fieldName = "automationWorkflows[" + index + "]";
            result.add(automationWorkflow(object(values.get(index), fieldName), fieldName));
        }
        return List.copyOf(result);
    }

    private static AutomationWorkflow automationWorkflow(
            JsonObject value,
            String fieldName
    ) throws IOException {
        requireFields(
                value,
                AUTOMATION_WORKFLOW_FIELDS,
                AUTOMATION_WORKFLOW_REQUIRED_FIELDS
        );
        TaskSettings settings = value.has("settings")
                ? taskSettings(object(value.get("settings"), fieldName + ".settings"))
                : TaskSettings.defaults();
        return new AutomationWorkflow(
                string(value, "id", 64),
                string(value, "name", 256),
                automationSteps(array(value, "steps")),
                settings
        );
    }

    private static List<AutomationStep> automationSteps(JsonArray values) throws IOException {
        require(values.size() <= 128, "steps has too many entries");
        List<AutomationStep> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(values.get(index), "steps[" + index + "]");
            requireFields(
                    value,
                    Set.of("id", "type", "parameters"),
                    Set.of("id", "type", "parameters")
            );
            AutomationStepType type;
            try {
                type = AutomationStepType.valueOf(string(value, "type", 64));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unknown automation step type", exception);
            }
            result.add(new AutomationStep(
                    string(value, "id", 64),
                    type,
                    automationParameters(
                    object(value.get("parameters"), "parameters"),
                    type
            )
            ));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> automationParameters(
            JsonObject value,
            AutomationStepType type
    ) throws IOException {
        Set<String> required = requiredAutomationParameters(type);
        Set<String> optional = optionalAutomationParameters(type);
        requireFields(value, union(required, optional), required);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            JsonElement element = entry.getValue();
            require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
                    "parameters." + entry.getKey() + " must be a string");
            String parameter = element.getAsString();
            require(!parameter.isBlank(), "parameters." + entry.getKey()
                    + " must not be blank");
            require(parameter.length() <= 256,
                    "parameters." + entry.getKey() + " is too long");
            if ("seconds".equals(entry.getKey()) || "count".equals(entry.getKey())) {
                requirePositiveInteger(parameter, "parameters." + entry.getKey());
            }
            result.put(entry.getKey(), parameter);
        }
        if (type == AutomationStepType.RUN_PICKUP) {
            if (CustomPickupRules.fromParameters(result).isEmpty()) {
                require(result.containsKey("workflowId"), "Missing JSON field: workflowId");
            }
            String waitForItems = result.get("waitForItems");
            require(waitForItems == null || "true".equals(waitForItems)
                            || "false".equals(waitForItems),
                    "parameters.waitForItems must be true or false");
        }
        return Map.copyOf(result);
    }

    private static Set<String> requiredAutomationParameters(AutomationStepType type) {
        return switch (type) {
            case NAVIGATE -> Set.of("coordinateId");
            case WAIT -> Set.of("seconds");
            case RUN_PICKUP -> Set.of();
            case TAKE_ITEMS, PUT_ITEMS -> Set.of("containerId", "itemId", "count");
            case TRANSFER_ITEMS -> Set.of(
                    "sourceContainerId",
                    "destination",
                    "itemId",
                    "count"
            );
            case CHECK_INVENTORY, ACQUIRE_MATERIALS, CRAFT_ITEM ->
                    Set.of("itemId", "count");
        };
    }

    private static Set<String> optionalAutomationParameters(AutomationStepType type) {
        return type == AutomationStepType.RUN_PICKUP
                ? Set.of(
                        "workflowId", "regionId", "waitForItems", "standbyCoordinateId",
                        "firstCornerCoordinateId", "secondCornerCoordinateId", "cacheIds",
                        "idleSeconds", "classificationTiming"
                )
                : Set.of();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static void requirePositiveInteger(String value, String fieldName)
            throws IOException {
        require(value.matches("[1-9][0-9]*"), fieldName + " must be a positive integer");
        try {
            Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IOException(fieldName + " is outside the long range", exception);
        }
    }

    private static List<NamedCache> namedCaches(JsonArray values) throws IOException {
        List<NamedCache> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(values.get(index), "cacheContainers[" + index + "]");
            requireFields(value, NAMED_CACHE_FIELDS, NAMED_CACHE_FIELDS);
            result.add(new NamedCache(
                    string(value, "id", 64),
                    string(value, "name", 256),
                    string(value, "coordinateId", 64),
                    string(value, "dimension", 128),
                    position(object(value.get("position"), "position")),
                    ContainerType.valueOf(string(value, "type", 64))
            ));
        }
        return List.copyOf(result);
    }

    private static List<PickupWorkflow> pickupWorkflows(JsonArray values)
            throws IOException {
        List<PickupWorkflow> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String fieldName = "pickupWorkflows[" + index + "]";
            result.add(pickupWorkflow(
                    object(values.get(index), fieldName),
                    fieldName,
                    null
            ));
        }
        return List.copyOf(result);
    }

    private static PickupWorkflow pickupWorkflow(
            JsonObject value,
            String fieldName,
            TaskSettings defaultSettings
    ) throws IOException {
        requireFields(
                value,
                PICKUP_WORKFLOW_FIELDS,
                PICKUP_WORKFLOW_REQUIRED_FIELDS
        );
        TaskSettings settings = value.has("settings")
                ? taskSettings(object(value.get("settings"), fieldName + ".settings"))
                : defaultSettings == null ? TaskSettings.defaults() : defaultSettings;
        if (defaultSettings != null && !defaultSettings.equals(settings)) {
            throw new IOException(
                    fieldName + ".settings must match the task definition settings"
            );
        }
        return new PickupWorkflow(
                string(value, "id", 64),
                string(value, "name", 256),
                string(value, "dimension", 128),
                regionBindings(array(value, "regions")),
                strings(array(value, "cacheIds"), "cacheIds", 64),
                CachePolicy.valueOf(string(value, "cachePolicy", 64)),
                ClassificationTiming.valueOf(string(
                        value,
                        "classificationTiming",
                        64
                )),
                CompletionMode.valueOf(string(value, "completionMode", 64)),
                Duration.ofSeconds(nonNegativeLong(value, "completionIdleDelay")),
                settings
        );
    }

    private static List<PickupRegionBinding> regionBindings(JsonArray values)
            throws IOException {
        List<PickupRegionBinding> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(values.get(index), "regions[" + index + "]");
            requireFields(
                    value,
                    Set.of("regionId", "priority", "idleDelay"),
                    Set.of("regionId", "priority", "idleDelay")
            );
            result.add(new PickupRegionBinding(
                    string(value, "regionId", 64),
                    integer(value, "priority"),
                    Duration.ofSeconds(nonNegativeLong(value, "idleDelay"))
            ));
        }
        return List.copyOf(result);
    }

    private static PickupShape shape(JsonObject value) throws IOException {
        String type = string(value, "type", 32);
        if ("SPHERE".equals(type)) {
            requireFields(value, SPHERE_SHAPE_FIELDS, SPHERE_SHAPE_FIELDS);
            return new SpherePickupShape(
                    string(value, "centerCoordinateId", 64),
                    position(object(value.get("center"), "center")),
                    integer(value, "radius")
            );
        }
        if ("CUBOID".equals(type)) {
            requireFields(value, CUBOID_SHAPE_FIELDS, CUBOID_SHAPE_FIELDS);
            return new CuboidPickupShape(
                    string(value, "firstCornerCoordinateId", 64),
                    string(value, "secondCornerCoordinateId", 64),
                    position(object(value.get("firstCorner"), "firstCorner")),
                    position(object(value.get("secondCorner"), "secondCorner"))
            );
        }
        throw new IOException("Unknown pickup shape type: " + type);
    }

    private static List<ConfigCoordinatePointInfo> coordinatePointInfos(JsonArray values)
            throws IOException {
        List<ConfigCoordinatePointInfo> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(
                    values.get(index),
                    "coordinatePoints[" + index + "]"
            );
            requireFields(
                    value,
                    Set.of("id", "name", "dimension", "position"),
                    Set.of("id", "name", "dimension", "position")
            );
            String id = string(value, "id", 64);
            require(SAFE_ID.matcher(id).matches(),
                    "coordinate point id must match [a-z0-9][a-z0-9._-]{0,63}");
            require(ids.add(id), "Duplicate coordinate point id: " + id);
            result.add(new ConfigCoordinatePointInfo(
                    id,
                    string(value, "name", 256),
                    string(value, "dimension", 128),
                    containerPosition(object(value.get("position"), "position"))
            ));
        }
        return List.copyOf(result);
    }

    private static BlockPosition position(JsonObject value) throws IOException {
        requireFields(
                value,
                Set.of("x", "y", "z"),
                Set.of("x", "y", "z")
        );
        return new BlockPosition(
                integer(value, "x"),
                integer(value, "y"),
                integer(value, "z")
        );
    }

    private static ContainerPosition containerPosition(JsonObject value) throws IOException {
        BlockPosition position = position(value);
        return new ContainerPosition(position.x(), position.y(), position.z());
    }

    private static List<String> strings(
            JsonArray values,
            String fieldName,
            int maximumLength
    ) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
                    fieldName + " values must be strings");
            String text = value.getAsString();
            require(!text.isBlank(), fieldName + " values must not be blank");
            require(text.length() <= maximumLength, fieldName + " value is too long");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static JsonObject object(JsonElement value, String fieldName)
            throws IOException {
        require(value != null && value.isJsonObject(), fieldName + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject value, String fieldName)
            throws IOException {
        JsonElement element = value.get(fieldName);
        require(element != null && element.isJsonArray(), fieldName + " must be an array");
        return element.getAsJsonArray();
    }

    private static String string(JsonObject value, String fieldName, int maximumLength)
            throws IOException {
        JsonElement element = value.get(fieldName);
        require(element != null && element.isJsonPrimitive(),
                fieldName + " must be a string");
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        require(primitive.isString(), fieldName + " must be a string");
        String result = primitive.getAsString();
        require(!result.isBlank(), fieldName + " must not be blank");
        require(result.length() <= maximumLength, fieldName + " is too long");
        return result;
    }

    private static int integer(JsonObject value, String fieldName) throws IOException {
        String number = integerText(value, fieldName);
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException exception) {
            throw new IOException(fieldName + " is outside the integer range", exception);
        }
    }

    private static long nonNegativeLong(JsonObject value, String fieldName)
            throws IOException {
        String number = integerText(value, fieldName);
        try {
            long result = Long.parseLong(number);
            require(result >= 0, fieldName + " must not be negative");
            return result;
        } catch (NumberFormatException exception) {
            throw new IOException(fieldName + " is outside the long range", exception);
        }
    }

    private static String integerText(JsonObject value, String fieldName)
            throws IOException {
        JsonElement element = value.get(fieldName);
        require(element != null && element.isJsonPrimitive(),
                fieldName + " must be an integer");
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        require(primitive.isNumber(), fieldName + " must be an integer");
        String number = primitive.getAsString();
        require(number.matches("-?(0|[1-9][0-9]*)"), fieldName + " must be an integer");
        return number;
    }

    private static void requireFields(
            JsonObject value,
            Set<String> allowed,
            Set<String> required
    ) throws IOException {
        for (String field : value.keySet()) {
            require(allowed.contains(field), "Unknown JSON field: " + field);
        }
        for (String field : required) {
            require(value.has(field), "Missing JSON field: " + field);
        }
    }

    private static void requireEnd(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.END_DOCUMENT, "JSON body has trailing content");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private static InvalidRequestException malformed(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "Malformed JSON body";
        }
        return new InvalidRequestException(message);
    }
}
