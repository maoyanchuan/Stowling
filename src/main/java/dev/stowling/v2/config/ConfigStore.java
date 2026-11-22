package dev.stowling.v2.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.stowling.v2.category.CategoryMarkerSource;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path path;
    private byte[] validatedBytes;
    private V2ConfigSnapshot validatedSnapshot;

    public ConfigStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public synchronized V2ConfigSnapshot loadOrReset() {
        if (Files.notExists(path)) {
            write(AppConfig.empty());
        }
        try {
            return read();
        } catch (V2ValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new V2ValidationException(
                    V2ValidationException.diagnosticMessage(exception), exception);
        }
    }

    public synchronized V2ConfigSnapshot read() {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (Arrays.equals(bytes, validatedBytes)) {
                return validatedSnapshot;
            }
            AppConfig parsed = parseConfig(
                    new String(bytes, StandardCharsets.UTF_8)
            );
            if (parsed == null) {
                throw new V2ValidationException("configuration is empty");
            }
            AppConfig config = TaskFlowNormalizer.normalize(parsed);
            ConfigValidator.requireValid(config);
            validatedSnapshot = new V2ConfigSnapshot(revision(bytes), config);
            validatedBytes = bytes;
            return validatedSnapshot;
        } catch (IOException exception) {
            throw new V2ValidationException("无法读取配置文件，请检查文件和读取权限。", exception);
        }
    }

    private static AppConfig parseConfig(String content) {
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new V2ValidationException("configuration must be an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            requireSchema(root);
            requireFields(
                    root,
                    "$",
                    Set.of(
                            "schemaVersion",
                            "coordinates",
                            "tasks",
                            "regions",
                            "categoryZones",
                            "triggers",
                            "taskFolders",
                            "autoClassifyTasks", "coordinateCatalog"
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
            JsonArray tasks = requireArray(root.get("tasks"), "tasks");
            for (int index = 0; index < tasks.size(); index++) {
                String path = "tasks[" + index + "]";
                JsonObject taskObject = requireObject(tasks.get(index), path);
                requireCleanupPolicy(taskObject.get("cleanupPolicy"));
                requireFields(
                        taskObject,
                        path,
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
                                "folderId",
                                "cleanupPolicy"
                        ),
                        Set.of("id", "name", "steps")
                );
                requireStepObjects(taskObject.get("steps"), path + ".steps");
            }
            return GSON.fromJson(root, AppConfig.class);
        } catch (JsonParseException exception) {
            throw new V2ValidationException(
                    V2ValidationException.diagnosticMessage(exception), exception);
        }
    }

    private static void requireSchema(JsonObject root) {
        JsonElement value = root.get("schemaVersion");
        if (value == null || !value.isJsonPrimitive()) {
            throw new V2ValidationException("schemaVersion 必须是整数。");
        }
        if (!value.getAsJsonPrimitive().isNumber()) {
            throw new V2ValidationException("schemaVersion 必须是整数。");
        }
        int version;
        try {
            version = value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new V2ValidationException("schemaVersion 必须是整数。", exception);
        }
        if (version != AppConfig.CURRENT_SCHEMA_VERSION) {
            throw new V2ValidationException("不支持 schemaVersion " + version
                    + "。请用版本 " + AppConfig.CURRENT_SCHEMA_VERSION
                    + " 重新创建配置。原配置文件已保留。");
        }
    }

    private static void requireStepObjects(JsonElement value, String path) {
        JsonArray steps = requireArray(value, path);
        for (int index = 0; index < steps.size(); index++) {
            String stepPath = path + "[" + index + "]";
            JsonObject step = requireObject(steps.get(index), stepPath);
            requireFields(
                    step,
                    stepPath,
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
            requireStringParameters(step.get("parameters"), stepPath + ".parameters");
            if (step.has("steps")) {
                requireStepObjects(step.get("steps"), stepPath + ".steps");
            }
            if (step.has("otherwise")) {
                requireStepObjects(step.get("otherwise"), stepPath + ".otherwise");
            }
        }
    }

    private static void requireTaskTriggerObjects(JsonElement value) {
        for (JsonElement item : requireArray(value, "triggers")) {
            JsonObject trigger = requireObject(item, "task trigger");
            requireFields(
                    trigger,
                    "triggers",
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
                            "radius",
                            "disabled",
                            "requiredProtectedFood",
                            "schedule"
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

    private static void requireCleanupPolicy(JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        JsonObject policy = requireObject(value, "cleanupPolicy");
        requireFields(policy, "cleanupPolicy",
                Set.of("destinationRef", "resumeMode", "emptyBoxRefs", "packingPositionRef"), Set.of());
        JsonElement mode = policy.get("resumeMode");
        if (mode != null && !mode.isJsonNull() && (!mode.isJsonPrimitive()
                || !mode.getAsJsonPrimitive().isString()
                || !Set.of("RESTART", "CONTINUE").contains(mode.getAsString()))) {
            throw new V2ValidationException("cleanupPolicy.resumeMode is invalid");
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
                "cacheLocationRef",
                "classificationAreas",
                "collectionPriority"
        );
        Set<String> allowed = new java.util.HashSet<>(fields);
        allowed.add("pickupRadius");
        allowed.add("overflowEnabled");
        allowed.add("overflowDestinationRef");
        allowed.add("cleanupPolicy");
        allowed.add("unstackableRegionRef");
        allowed.add("cachePolicy");
        JsonArray definitions = requireArray(value, "autoClassifyTasks");
        for (int index = 0; index < definitions.size(); index++) {
            String path = "autoClassifyTasks[" + index + "]";
            JsonObject definition = requireObject(definitions.get(index), path);
            requireFields(definition, path, allowed, fields);
            requireCleanupPolicy(definition.get("cleanupPolicy"));
            requireCachePolicy(definition.get("cachePolicy"));
            requireAutoClassifyAreas(
                    definition.get("classificationAreas"), path + ".classificationAreas");
        }
    }

    private static void requireAutoClassifyAreas(JsonElement value, String path) {
        JsonArray areas = requireArray(value, path);
        for (int index = 0; index < areas.size(); index++) {
            String areaPath = path + "[" + index + "]";
            JsonObject area = requireObject(areas.get(index), areaPath);
            requireFields(area, areaPath, Set.of(
                    "categoryZoneRef",
                    "storagePolicy"
            ), Set.of("categoryZoneRef"));
        }
    }

    private static void requireCachePolicy(JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        JsonObject policy = requireObject(value, "cachePolicy");
        requireFields(policy, "cachePolicy", Set.of("enabled", "mode", "thresholdPercent"),
                Set.of("mode", "thresholdPercent"));
        JsonElement enabled = policy.get("enabled");
        if (enabled != null && (!enabled.isJsonPrimitive()
                || !enabled.getAsJsonPrimitive().isBoolean())) {
            throw new V2ValidationException("cachePolicy.enabled 必须是布尔值");
        }
        if (!policy.get("mode").isJsonPrimitive()
                || !Set.of("PACK_FIRST", "CLASSIFY_FIRST")
                        .contains(policy.get("mode").getAsString())) {
            throw new V2ValidationException("cachePolicy.mode is invalid");
        }
        JsonElement percent = policy.get("thresholdPercent");
        if (!percent.isJsonPrimitive() || !percent.getAsJsonPrimitive().isNumber()
                || !percent.getAsString().matches("[0-9]+")) {
            throw new V2ValidationException("cachePolicy.thresholdPercent 必须是整数");
        }
    }

    private static void requireTaskFolderObjects(JsonElement value) {
        for (JsonElement item : requireArray(value, "taskFolders")) {
            requireExactFields(
                    requireObject(item, "task folder"),
                    "taskFolders",
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
            requireFields(coordinate, "coordinates", allowed, required);
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
            requireExactFields(requireObject(item, "region"), "regions", fields);
        }
    }

    private static void requireCategoryZoneObjects(JsonElement value) {
        JsonArray zones = requireArray(value, "categoryZones");
        for (int index = 0; index < zones.size(); index++) {
            String zonePath = "categoryZones[" + index + "]";
            JsonObject zone = requireObject(zones.get(index), zonePath);
            requireFields(
                    zone,
                    zonePath,
                    Set.of("id", "name", "bindings", "expansion"),
                    Set.of("id", "name", "bindings")
            );
            JsonElement expansion = zone.get("expansion");
            if (expansion != null && !expansion.isJsonNull()) {
                JsonObject expansionObject = requireObject(
                        expansion,
                        "category expansion"
                );
                requireExactFields(
                        expansionObject,
                        zonePath + ".expansion",
                        Set.of(
                                "area",
                                "direction",
                                "step",
                                "templateCount"
                        )
                );
                requireArea(
                        expansionObject.get("area"),
                        "category expansion area"
                );
            }
            String bindingsPath = zonePath + ".bindings";
            JsonArray bindings = requireArray(zone.get("bindings"), bindingsPath);
            for (int bindingIndex = 0; bindingIndex < bindings.size(); bindingIndex++) {
                String bindingPath = bindingsPath + "[" + bindingIndex + "]";
                JsonObject binding = requireObject(
                        bindings.get(bindingIndex), bindingPath
                );
                requireFields(
                        binding,
                        bindingPath,
                        Set.of(
                                "id",
                                "markerArea",
                                "storageArea",
                                "ruleId",
                                "ignoredMarkerBlockIds",
                                "containerOverrides",
                                "standingArea",
                                "storagePolicy",
                                "wholeStorage"
                        ),
                        Set.of("id", "markerArea", "storageArea", "ruleId")
                );
                requireArea(binding.get("markerArea"), "markerArea");
                requireArea(binding.get("storageArea"), "storageArea");
                JsonElement standing = binding.get("standingArea");
                if (standing != null && !standing.isJsonNull()) {
                    requireArea(standing, "standingArea");
                }
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
                    "containerOverrides",
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
                    "storagePosition",
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
                name,
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

    private static void requireExactFields(JsonObject value, String path, Set<String> fields) {
        requireFields(value, path, fields, fields);
    }

    private static void requireFields(
            JsonObject value,
            String path,
            Set<String> allowed,
            Set<String> required
    ) {
        if (!allowed.containsAll(value.keySet())) {
            var unknown = value.keySet().stream().filter(key -> !allowed.contains(key))
                    .sorted().toList();
            throw new V2ValidationException(path + " 包含不支持的字段 " + unknown);
        }
        if (!value.keySet().containsAll(required)) {
            var missing = required.stream().filter(key -> !value.has(key)).sorted().toList();
            throw new V2ValidationException(path + " 缺少必填字段 " + missing);
        }
    }

    private static void requireStringParameters(JsonElement value, String path) {
        JsonObject parameters = requireObject(value, path);
        for (var entry : parameters.entrySet()) {
            JsonElement parameter = entry.getValue();
            if (!parameter.isJsonPrimitive()
                    || !parameter.getAsJsonPrimitive().isString()) {
                throw new V2ValidationException(
                        path + "." + entry.getKey() + " must be a string"
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
