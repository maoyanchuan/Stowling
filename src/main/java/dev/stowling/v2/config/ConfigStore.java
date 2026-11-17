package dev.stowling.v2.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.TaskFlowNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path path;

    public ConfigStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public synchronized V2ConfigSnapshot loadOrReset() {
        if (Files.isRegularFile(path)) {
            try {
                V2ConfigSnapshot current = read();
                ConfigValidator.requireValid(current.config());
                return current;
            } catch (RuntimeException exception) {
                String detail = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                throw new V2ValidationException(
                        "existing configuration is invalid and was preserved: "
                                + detail,
                        exception
                );
            }
        }
        AppConfig empty = AppConfig.empty();
        write(empty);
        return read();
    }

    public synchronized V2ConfigSnapshot read() {
        try {
            byte[] bytes = Files.readAllBytes(path);
            AppConfig parsed = parseConfig(
                    new String(bytes, StandardCharsets.UTF_8)
            );
            if (parsed == null) {
                throw new V2ValidationException("configuration is empty");
            }
            AppConfig config = TaskFlowNormalizer.normalize(parsed);
            ConfigValidator.requireValid(config);
            return new V2ConfigSnapshot(revision(bytes), config);
        } catch (IOException | JsonParseException exception) {
            throw new V2ValidationException("configuration cannot be read");
        }
    }

    private static AppConfig parseConfig(String content) {
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new V2ValidationException("configuration must be an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            upgradeSchema(root);
            requireFields(
                    root,
                    Set.of(
                            "schemaVersion",
                            "coordinates",
                            "tasks",
                            "regions",
                            "categoryZones",
                            "triggers",
                            "taskFolders",
                            "autoClassifyTasks"
                    ),
                    Set.of("schemaVersion", "coordinates", "tasks")
            );
            requireCoordinateObjects(root.get("coordinates"));
            if (root.has("regions")) {
                requireRegionObjects(root.get("regions"));
            }
            if (root.has("categoryZones")) {
                requireCategoryZoneObjects(root.get("categoryZones"));
            }
            if (root.has("triggers")) {
                requireTaskTriggerObjects(root.get("triggers"));
            }
            if (root.has("taskFolders")) {
                requireTaskFolderObjects(root.get("taskFolders"));
            }
            if (root.has("autoClassifyTasks")) {
                requireAutoClassifyObjects(root.get("autoClassifyTasks"));
            }
            for (JsonElement task : requireArray(root.get("tasks"), "tasks")) {
                JsonObject taskObject = requireObject(task, "task");
                requireFields(
                        taskObject,
                        Set.of(
                                "id",
                                "name",
                                "priority",
                                "repeatWhenIdle",
                                "steps",
                                "trigger",
                                "interruptionMode",
                                "interruptionTaskRef",
                                "defaultInterruptionFlow",
                                "requeueAfterInterruption",
                                "folderId"
                        ),
                        Set.of("id", "name", "steps")
                );
                requireStepObjects(taskObject.get("steps"));
            }
            return GSON.fromJson(root, AppConfig.class);
        } catch (JsonParseException exception) {
            throw new V2ValidationException("configuration cannot be read");
        }
    }

    private static void upgradeSchema(JsonObject root) {
        JsonElement value = root.get("schemaVersion");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return;
        }
        int version = value.getAsInt();
        if (version >= 7 && version <= 10) {
            if (!root.has("autoClassifyTasks")) {
                root.add("autoClassifyTasks", new JsonArray());
            }
            ensureAutoClassifyPhaseFlags(root);
            migrateAutoClassifyAreas(root);
            root.addProperty("schemaVersion", AppConfig.CURRENT_SCHEMA_VERSION);
        }
    }

    private static void ensureAutoClassifyPhaseFlags(JsonObject root) {
        JsonElement value = root.get("autoClassifyTasks");
        if (value == null || !value.isJsonArray()) {
            return;
        }
        for (JsonElement item : value.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject definition = item.getAsJsonObject();
            if (!definition.has("collectionEnabled")) {
                definition.addProperty("collectionEnabled", true);
            }
            if (!definition.has("classificationEnabled")) {
                definition.addProperty("classificationEnabled", true);
            }
        }
    }

    private static void migrateAutoClassifyAreas(JsonObject root) {
        JsonElement tasksValue = root.get("autoClassifyTasks");
        if (tasksValue == null || !tasksValue.isJsonArray()) {
            return;
        }
        JsonElement zonesValue = root.get("categoryZones");
        JsonArray zones = zonesValue != null && zonesValue.isJsonArray()
                ? zonesValue.getAsJsonArray()
                : new JsonArray();
        for (JsonElement item : tasksValue.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject definition = item.getAsJsonObject();
            if (definition.has("classificationAreas")) {
                consolidateClassificationAreas(definition);
                definition.remove("categoryZoneRef");
                definition.remove("markerSources");
                continue;
            }
            String categoryZoneRef = stringValue(
                    definition.get("categoryZoneRef")
            );
            String zoneId = categoryZoneRef.contains(":")
                    ? categoryZoneRef.substring(categoryZoneRef.indexOf(':') + 1)
                    : "";
            JsonElement sourcesValue = definition.get("markerSources");
            JsonArray markerSources = sourcesValue != null
                    && sourcesValue.isJsonArray()
                    ? sourcesValue.getAsJsonArray().deepCopy()
                    : new JsonArray();
            JsonArray areas = new JsonArray();
            for (JsonElement zoneItem : zones) {
                if (zoneItem == null || !zoneItem.isJsonObject()) {
                    continue;
                }
                JsonObject zone = zoneItem.getAsJsonObject();
                if (!zoneId.equals(stringValue(zone.get("id")))) {
                    continue;
                }
                JsonElement bindings = zone.get("bindings");
                if (bindings == null || !bindings.isJsonArray()) {
                    continue;
                }
                String ruleId = firstRuleId(bindings.getAsJsonArray());
                areas.add(classificationArea(
                        categoryZoneRef,
                        ruleId,
                        markerSources
                ));
            }
            definition.add("classificationAreas", areas);
            definition.remove("categoryZoneRef");
            definition.remove("markerSources");
        }
    }

    private static void consolidateClassificationAreas(
            JsonObject definition
    ) {
        JsonElement value = definition.get("classificationAreas");
        if (value == null || !value.isJsonArray()) {
            return;
        }
        Map<String, JsonObject> byZone = new LinkedHashMap<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject existing = item.getAsJsonObject();
            String zoneRef = stringValue(existing.get("categoryZoneRef"));
            if (zoneRef.isBlank() || byZone.containsKey(zoneRef)) {
                continue;
            }
            JsonArray markerSources = existing.has("markerSources")
                    ? existing.get("markerSources").getAsJsonArray().deepCopy()
                    : new JsonArray();
            byZone.put(zoneRef, classificationArea(
                    zoneRef,
                    stringValue(existing.get("ruleId")),
                    markerSources
            ));
        }
        JsonArray consolidated = new JsonArray();
        byZone.values().forEach(consolidated::add);
        definition.add("classificationAreas", consolidated);
    }

    private static JsonObject classificationArea(
            String categoryZoneRef,
            String ruleId,
            JsonArray markerSources
    ) {
        JsonObject area = new JsonObject();
        area.addProperty("categoryZoneRef", categoryZoneRef);
        area.addProperty("ruleId", ruleId);
        area.add(
                "markerSources",
                compatibleMarkerSources(ruleId, markerSources)
        );
        return area;
    }

    private static String firstRuleId(JsonArray bindings) {
        for (JsonElement item : bindings) {
            if (item != null && item.isJsonObject()) {
                String ruleId = stringValue(
                        item.getAsJsonObject().get("ruleId")
                );
                if (!ruleId.isBlank()) {
                    return ruleId;
                }
            }
        }
        return "DIRECT_BLOCK_ID";
    }

    private static String stringValue(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static JsonArray compatibleMarkerSources(
            String ruleId,
            JsonArray configured
    ) {
        JsonArray result = new JsonArray();
        for (JsonElement source : configured) {
            if (source == null || source.isJsonNull()) {
                continue;
            }
            String value = source.getAsString();
            if (("CONTAINER_CONTENTS".equals(ruleId)
                    && "CONTAINER_CONTENTS".equals(value))
                    || ("DIRECT_BLOCK_ID".equals(ruleId)
                    && !"CONTAINER_CONTENTS".equals(value))) {
                result.add(value);
            }
        }
        if (result.isEmpty()) {
            result.add("CONTAINER_CONTENTS".equals(ruleId)
                    ? "CONTAINER_CONTENTS"
                    : "PLACED_ITEM");
        }
        return result;
    }

    private static void requireStepObjects(JsonElement value) {
        for (JsonElement item : requireArray(value, "steps")) {
            JsonObject step = requireObject(item, "step");
            requireFields(
                    step,
                    Set.of(
                            "id",
                            "kind",
                            "moduleId",
                            "parameters",
                            "steps",
                            "otherwise"
                    ),
                    Set.of("id", "parameters")
            );
            requireStringParameters(step.get("parameters"));
            if (step.has("steps")) {
                requireStepObjects(step.get("steps"));
            }
            if (step.has("otherwise")) {
                requireStepObjects(step.get("otherwise"));
            }
        }
    }

    private static void requireTaskTriggerObjects(JsonElement value) {
        for (JsonElement item : requireArray(value, "triggers")) {
            JsonObject trigger = requireObject(item, "task trigger");
            requireFields(
                    trigger,
                    Set.of(
                            "id",
                            "name",
                            "type",
                            "targetTaskId",
                            "priority",
                            "intervalTicks",
                            "cooldownTicks",
                            "coordinateId",
                            "itemFilter",
                            "foodLevelThreshold",
                            "cacheRegionId",
                            "radius"
                    ),
                    Set.of(
                            "id",
                            "name",
                            "type",
                            "targetTaskId",
                            "priority",
                            "intervalTicks",
                            "cooldownTicks"
                    )
            );
        }
    }

    private static void requireAutoClassifyObjects(JsonElement value) {
        Set<String> fields = Set.of(
                "id",
                "name",
                "enabled",
                "collectionEnabled",
                "classificationEnabled",
                "detectionCoordinateRef",
                "pickupRadius",
                "cacheRegionRef",
                "classificationAreas",
                "collectionPriority"
        );
        for (JsonElement item
                : requireArray(value, "autoClassifyTasks")) {
            JsonObject definition = requireObject(
                    item,
                    "auto classify task"
            );
            requireExactFields(definition, fields);
            requireAutoClassifyAreas(definition.get("classificationAreas"));
        }
    }

    private static void requireAutoClassifyAreas(JsonElement value) {
        for (JsonElement item : requireArray(value, "classificationAreas")) {
            JsonObject area = requireObject(item, "classification area");
            requireExactFields(area, Set.of(
                    "categoryZoneRef",
                    "ruleId",
                    "markerSources"
            ));
            requireStringArray(area.get("markerSources"), "markerSources");
        }
    }

    private static void requireTaskFolderObjects(JsonElement value) {
        for (JsonElement item : requireArray(value, "taskFolders")) {
            requireExactFields(
                    requireObject(item, "task folder"),
                    Set.of("id", "name")
            );
        }
    }

    private static void requireCoordinateObjects(JsonElement value) {
        Set<String> required = Set.of("id", "name", "x", "y", "z");
        Set<String> allowed = Set.of(
                "id",
                "name",
                "x",
                "y",
                "z",
                "dimension",
                "radius"
        );
        for (JsonElement item : requireArray(value, "coordinates")) {
            JsonObject coordinate = requireObject(item, "coordinate");
            if (!allowed.containsAll(coordinate.keySet())
                    || !coordinate.keySet().containsAll(required)) {
                throw new V2ValidationException(
                        "configuration fields do not match schema"
                );
            }
        }
    }

    private static void requireRegionObjects(JsonElement value) {
        Set<String> fields = Set.of(
                "id",
                "name",
                "firstX",
                "firstY",
                "firstZ",
                "secondX",
                "secondY",
                "secondZ"
        );
        for (JsonElement item : requireArray(value, "regions")) {
            requireExactFields(requireObject(item, "region"), fields);
        }
    }

    private static void requireCategoryZoneObjects(JsonElement value) {
        for (JsonElement item : requireArray(value, "categoryZones")) {
            JsonObject zone = requireObject(item, "category zone");
            requireExactFields(zone, Set.of("id", "name", "bindings"));
            for (JsonElement bindingValue
                    : requireArray(zone.get("bindings"), "bindings")) {
                JsonObject binding = requireObject(
                        bindingValue,
                        "category binding"
                );
                requireFields(
                        binding,
                        Set.of(
                                "id",
                                "markerArea",
                                "storageArea",
                                "ruleId",
                                "ignoredMarkerBlockIds",
                                "containerOverrides"
                        ),
                        Set.of("id", "markerArea", "storageArea", "ruleId")
                );
                requireArea(binding.get("markerArea"), "markerArea");
                requireArea(binding.get("storageArea"), "storageArea");
                requireContainerOverrides(binding.get("containerOverrides"));
            }
        }
    }

    private static void requireContainerOverrides(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        for (JsonElement item
                : requireArray(value, "containerOverrides")) {
            JsonObject override = requireObject(
                    item,
                    "container override"
            );
            requireExactFields(
                    override,
                    Set.of(
                            "id",
                            "storagePosition",
                            "mode",
                            "itemIds"
                    )
            );
            requireExactFields(
                    requireObject(
                            override.get("storagePosition"),
                            "storagePosition"
                    ),
                    Set.of("x", "y", "z")
            );
            requireStringArray(override.get("itemIds"), "itemIds");
        }
    }

    private static void requireStringArray(
            JsonElement value,
            String name
    ) {
        for (JsonElement item : requireArray(value, name)) {
            if (!item.isJsonPrimitive()
                    || !item.getAsJsonPrimitive().isString()) {
                throw new V2ValidationException(
                        name + " must contain strings"
                );
            }
        }
    }

    private static void requireArea(JsonElement value, String name) {
        requireExactFields(
                requireObject(value, name),
                Set.of(
                        "firstX",
                        "firstY",
                        "firstZ",
                        "secondX",
                        "secondY",
                        "secondZ"
                )
        );
    }

    private static JsonArray requireArray(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) {
            throw new V2ValidationException(name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new V2ValidationException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireExactFields(JsonObject value, Set<String> fields) {
        requireFields(value, fields, fields);
    }

    private static void requireFields(
            JsonObject value,
            Set<String> allowed,
            Set<String> required
    ) {
        if (!allowed.containsAll(value.keySet())
                || !value.keySet().containsAll(required)) {
            throw new V2ValidationException("configuration fields do not match schema");
        }
    }

    private static void requireStringParameters(JsonElement value) {
        JsonObject parameters = requireObject(value, "parameters");
        for (JsonElement parameter : parameters.asMap().values()) {
            if (!parameter.isJsonPrimitive()
                    || !parameter.getAsJsonPrimitive().isString()) {
                throw new V2ValidationException(
                        "parameter values must be strings"
                );
            }
        }
    }

    public synchronized V2ConfigSnapshot save(
            String expectedRevision,
            AppConfig config
    ) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        AppConfig normalized = TaskFlowNormalizer.normalize(config);
        ConfigValidator.requireValid(normalized);
        if (!read().revision().equals(expectedRevision)) {
            throw new V2RevisionConflictException();
        }
        backup();
        write(normalized);
        return read();
    }

    private void backup() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        try {
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new V2ValidationException("configuration backup failed");
        }
    }

    private void write(AppConfig config) {
        Path parent = path.getParent();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    temporary,
                    GSON.toJson(config) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new V2ValidationException("configuration cannot be written");
        }
    }

    private static String revision(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
