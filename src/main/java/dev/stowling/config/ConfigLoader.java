package dev.stowling.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public final class ConfigLoader {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion",
            "defaultIdleDelay",
            "pickupSites",
            "storageRoutes",
            "packingStations",
            "auditSchedules",
            "overflowContainer"
    );
    private final Gson gson;
    private final AtomicFileReplacer atomicFileReplacer;

    public ConfigLoader() {
        this(ConfigLoader::replaceAtomically);
    }

    ConfigLoader(AtomicFileReplacer atomicFileReplacer) {
        this.atomicFileReplacer = Objects.requireNonNull(
                atomicFileReplacer,
                "atomicFileReplacer"
        );
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Duration.class, new DurationJsonAdapter())
                .setPrettyPrinting()
                .create();
    }

    public StowlingConfig load(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ConfigLoadException("Unable to read config: " + path, exception);
        }
    }

    public StowlingConfig parse(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            requireObject(element);
            JsonObject root = element.getAsJsonObject();
            rejectUnknownRootFields(root);
            normalizeCompatibilityFields(root);
            StowlingConfig config = gson.fromJson(root, StowlingConfig.class);
            if (config == null) {
                throw new ConfigLoadException("Configuration root must be a JSON object");
            }
            ConfigValidator.requireValid(config);
            return config;
        } catch (ConfigLoadException | ConfigValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConfigLoadException("Unable to parse configuration", exception);
        }
    }

    public StowlingConfig load(Reader reader) {
        try {
            return parse(JsonParser.parseReader(reader).toString());
        } catch (RuntimeException exception) {
            if (exception instanceof ConfigLoadException
                    || exception instanceof ConfigValidationException) {
                throw exception;
            }
            throw new ConfigLoadException("Unable to parse configuration", exception);
        }
    }

    public void write(Path path, StowlingConfig config) {
        ConfigValidator.requireValid(config);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, gson.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Unable to write config: " + path, exception);
        }
    }

    public void writeAtomicWithBackup(Path path, StowlingConfig config) {
        ConfigValidator.requireValid(config);
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            Files.writeString(
                    temporary,
                    gson.toJson(config),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            if (Files.isRegularFile(target)) {
                Files.copy(
                        target,
                        backup,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
            }
            atomicFileReplacer.replace(temporary, target);
            temporary = null;
        } catch (IOException exception) {
            throw new ConfigLoadException("Unable to atomically write config: " + path, exception);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic config replacement is not supported", exception);
        }
    }

    private static void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The original configuration remains intact even if temporary cleanup fails.
        }
    }

    @FunctionalInterface
    interface AtomicFileReplacer {
        void replace(Path source, Path target) throws IOException;
    }

    private static void requireObject(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new ConfigLoadException("Configuration root must be a JSON object");
        }
    }

    private static void rejectUnknownRootFields(JsonObject root) {
        for (String field : root.keySet()) {
            if (!ROOT_FIELDS.contains(field)) {
                throw new ConfigLoadException("Unknown configuration field: " + field);
            }
        }
    }

    private static void normalizeCompatibilityFields(JsonObject root) {
        normalizePickupSites(root);
        normalizeStorageRoutes(root);
    }

    private static void normalizePickupSites(JsonObject root) {
        JsonElement sitesElement = root.get("pickupSites");
        if (sitesElement == null || !sitesElement.isJsonArray()) {
            return;
        }
        for (JsonElement siteElement : sitesElement.getAsJsonArray()) {
            if (!siteElement.isJsonObject()) {
                continue;
            }
            JsonObject site = siteElement.getAsJsonObject();
            JsonElement single = site.remove("cacheContainer");
            if (single != null && !site.has("cacheContainers")) {
                JsonArray containers = new JsonArray();
                containers.add(single);
                site.add("cacheContainers", containers);
            }
        }
    }

    private static void normalizeStorageRoutes(JsonObject root) {
        JsonElement routesElement = root.get("storageRoutes");
        if (routesElement == null || !routesElement.isJsonArray()) {
            return;
        }
        for (JsonElement routeElement : routesElement.getAsJsonArray()) {
            if (!routeElement.isJsonObject()) {
                continue;
            }
            JsonObject route = routeElement.getAsJsonObject();
            JsonElement single = route.remove("packingStationId");
            if (single != null && !single.isJsonNull() && !route.has("packingStationIds")) {
                JsonArray stations = new JsonArray();
                stations.add(single);
                route.add("packingStationIds", stations);
            }
            if (!route.has("packingThresholdMode")) {
                route.addProperty("packingThresholdMode", "ON_ITEM_COUNT");
            }
        }
    }
}
