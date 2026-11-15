package dev.stowling.v2.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.stowling.v2.model.AppConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
            } catch (RuntimeException ignored) {
                backup();
            }
        }
        AppConfig empty = AppConfig.empty();
        write(empty);
        return read();
    }

    public synchronized V2ConfigSnapshot read() {
        try {
            byte[] bytes = Files.readAllBytes(path);
            AppConfig config = parseConfig(new String(bytes, StandardCharsets.UTF_8));
            if (config == null) {
                throw new V2ValidationException("configuration is empty");
            }
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
            requireFields(
                    root,
                    Set.of(
                            "schemaVersion",
                            "coordinates",
                            "tasks",
                            "regions",
                            "categoryZones"
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
            for (JsonElement task : requireArray(root.get("tasks"), "tasks")) {
                JsonObject taskObject = requireObject(task, "task");
                requireFields(
                        taskObject,
                        Set.of(
                                "id",
                                "name",
                                "priority",
                                "repeatWhenIdle",
                                "steps"
                        ),
                        Set.of("id", "name", "steps")
                );
                for (JsonElement step : requireArray(taskObject.get("steps"), "steps")) {
                    JsonObject stepObject = requireObject(step, "step");
                    requireExactFields(
                            stepObject,
                            Set.of("id", "moduleId", "parameters")
                    );
                    requireStringParameters(stepObject.get("parameters"));
                }
            }
            return GSON.fromJson(root, AppConfig.class);
        } catch (JsonParseException exception) {
            throw new V2ValidationException("configuration cannot be read");
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
        ConfigValidator.requireValid(config);
        if (!read().revision().equals(expectedRevision)) {
            throw new V2RevisionConflictException();
        }
        backup();
        write(config);
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
