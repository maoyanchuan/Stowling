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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    public ConfigLoader() {
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
