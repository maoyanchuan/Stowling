package dev.stowling.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.domain.PickupShape;
import dev.stowling.schedule.TaskDefinition;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Set<String> V1_ROOT_FIELDS = Set.of(
            "schemaVersion",
            "defaultIdleDelay",
            "pickupSites",
            "storageRoutes",
            "packingStations",
            "auditSchedules",
            "overflowContainer"
    );
    private static final Set<String> V2_ROOT_FIELDS = Set.of(
            "schemaVersion",
            "pickupRegions",
            "cacheContainers",
            "pickupWorkflows",
            "storageRoutes",
            "packingStations",
            "auditSchedules",
            "overflowContainer"
    );
    private static final Set<String> V3_ROOT_FIELDS = Set.of(
            "schemaVersion",
            "coordinatePoints",
            "pickupRegions",
            "cacheContainers",
            "pickupWorkflows",
            "storageRoutes",
            "packingStations",
            "auditSchedules",
            "overflowContainer"
    );
    private static final Set<String> V4_ROOT_FIELDS = Set.of(
            "schemaVersion",
            "coordinatePoints",
            "pickupRegions",
            "cacheContainers",
            "pickupWorkflows",
            "automationWorkflows",
            "storageRoutes",
            "packingStations",
            "auditSchedules",
            "overflowContainer"
    );
    private static final Set<String> V5_ROOT_FIELDS = V4_ROOT_FIELDS;
    private static final Pattern SAFE_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}"
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
                .registerTypeAdapter(PickupShape.class, new PickupShapeJsonAdapter())
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
            int schemaVersion = readSchemaVersion(root);
            rejectUnknownRootFields(root, schemaVersion);
            normalizeCompatibilityFields(root);
            if (schemaVersion == 1) {
                root = migrateV1(root);
                schemaVersion = 2;
            }
            if (schemaVersion == 2) {
                root = migrateV2(root);
                schemaVersion = 3;
            }
            if (schemaVersion == 3) {
                root = migrateV3(root);
                schemaVersion = 4;
            }
            if (schemaVersion == 4) {
                root = migrateV4(root);
            }
            resolveCoordinateReferences(root);
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

    String canonicalJson(TaskDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        JsonElement tree = JsonParser.parseString(gson.toJson(definition));
        return gson.toJson(canonicalize(tree));
    }

    private static JsonElement canonicalize(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            TreeMap<String, JsonElement> fields = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                fields.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JsonElement> entry : fields.entrySet()) {
                sorted.add(entry.getKey(), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray sorted = new JsonArray();
            for (JsonElement value : element.getAsJsonArray()) {
                sorted.add(canonicalize(value));
            }
            return sorted;
        }
        return element.deepCopy();
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

    private static int readSchemaVersion(JsonObject root) {
        JsonElement element = root.get("schemaVersion");
        if (element == null || !element.isJsonPrimitive()) {
            for (String field : root.keySet()) {
                if (!V1_ROOT_FIELDS.contains(field)
                        && !V2_ROOT_FIELDS.contains(field)
                        && !V3_ROOT_FIELDS.contains(field)
                        && !V4_ROOT_FIELDS.contains(field)) {
                    throw new ConfigLoadException(
                            "Unknown configuration field: " + field
                    );
                }
            }
            throw new ConfigLoadException("Configuration requires schemaVersion");
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            throw new ConfigLoadException("schemaVersion must be an integer", exception);
        }
    }

    private static void rejectUnknownRootFields(JsonObject root, int schemaVersion) {
        Set<String> allowedFields;
        if (schemaVersion == 1) {
            allowedFields = V1_ROOT_FIELDS;
        } else if (schemaVersion == 2) {
            allowedFields = V2_ROOT_FIELDS;
        } else if (schemaVersion == 3) {
            allowedFields = V3_ROOT_FIELDS;
        } else if (schemaVersion == 4) {
            allowedFields = V4_ROOT_FIELDS;
        } else if (schemaVersion == StowlingConfig.CURRENT_SCHEMA_VERSION) {
            allowedFields = V5_ROOT_FIELDS;
        } else {
            throw new ConfigLoadException(
                    "Unsupported configuration schemaVersion: " + schemaVersion
            );
        }
        for (String field : root.keySet()) {
            if (!allowedFields.contains(field)) {
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

    private static JsonObject migrateV1(JsonObject source) {
        JsonObject migrated = new JsonObject();
        migrated.addProperty("schemaVersion", 2);
        JsonArray regions = new JsonArray();
        JsonArray caches = new JsonArray();
        JsonArray workflows = new JsonArray();
        Set<String> usedIds = new LinkedHashSet<>();
        Map<String, String> cacheIdByLocation = new LinkedHashMap<>();
        Map<String, String> cacheTypeByLocation = new LinkedHashMap<>();
        Map<String, String> cacheDescriptorByLegacyId = new LinkedHashMap<>();
        Map<String, String> migratedCacheIds = new LinkedHashMap<>();
        JsonArray sites = requireArray(source, "pickupSites");
        JsonElement defaultDelay = source.has("defaultIdleDelay")
                ? source.get("defaultIdleDelay").deepCopy()
                : seconds(60);

        for (int index = 0; index < sites.size(); index++) {
            JsonObject site = requireObject(sites.get(index), "pickupSites[" + index + "]");
            String oldSiteId = requireText(site, "id");
            String regionId = stableId(oldSiteId, "region-" + (index + 1), usedIds);
            String dimension = requireText(site, "dimension");
            JsonObject center = requireObject(
                    site.get("center"),
                    "pickupSites[" + index + "].center"
            );
            JsonElement idleDelay = site.has("idleDelay")
                    ? site.get("idleDelay").deepCopy()
                    : defaultDelay.deepCopy();

            JsonObject region = new JsonObject();
            region.addProperty("id", regionId);
            region.addProperty("name", oldSiteId);
            region.addProperty("dimension", dimension);
            region.add("standbyPosition", center.deepCopy());
            JsonObject shape = new JsonObject();
            shape.addProperty("type", "SPHERE");
            shape.add("center", center.deepCopy());
            shape.add("radius", requireField(site, "radius").deepCopy());
            region.add("shape", shape);
            regions.add(region);

            List<String> siteCacheIds = migrateSiteCaches(
                    requireArray(site, "cacheContainers"),
                    caches,
                    cacheIdByLocation,
                    cacheTypeByLocation,
                    cacheDescriptorByLegacyId,
                    migratedCacheIds,
                    usedIds
            );
            String workflowId = stableId(
                    oldSiteId + "-workflow",
                    "workflow-" + (index + 1),
                    usedIds
            );
            workflows.add(createMigratedWorkflow(
                    workflowId,
                    oldSiteId,
                    dimension,
                    regionId,
                    siteCacheIds,
                    idleDelay
            ));
        }

        migrated.add("pickupRegions", regions);
        migrated.add("cacheContainers", caches);
        migrated.add("pickupWorkflows", workflows);
        copyRequired(source, migrated, "storageRoutes");
        copyRequired(source, migrated, "packingStations");
        migrated.add(
                "auditSchedules",
                migrateAuditSchedules(requireArray(source, "auditSchedules"), migratedCacheIds)
        );
        copyRequired(source, migrated, "overflowContainer");
        return migrated;
    }

    private static JsonObject migrateV2(JsonObject source) {
        JsonObject migrated = source.deepCopy();
        migrated.addProperty("schemaVersion", 3);
        JsonArray coordinates = new JsonArray();
        Map<String, String> coordinateIds = new LinkedHashMap<>();
        Set<String> usedIds = pickupResourceIds(migrated);
        JsonArray regions = requireArray(migrated, "pickupRegions");
        for (int index = 0; index < regions.size(); index++) {
            JsonObject region = requireObject(
                    regions.get(index),
                    "pickupRegions[" + index + "]"
            );
            String name = requireText(region, "name");
            String dimension = requireText(region, "dimension");
            String standbyId = coordinateId(
                    coordinates,
                    coordinateIds,
                    usedIds,
                    dimension,
                    requireObject(region.get("standbyPosition"), "standbyPosition"),
                    name + " standby"
            );
            region.addProperty("standbyCoordinateId", standbyId);
            JsonObject shape = requireObject(region.get("shape"), "shape");
            migrateShapeCoordinates(
                    shape,
                    name,
                    dimension,
                    coordinates,
                    coordinateIds,
                    usedIds
            );
        }
        JsonArray caches = requireArray(migrated, "cacheContainers");
        for (int index = 0; index < caches.size(); index++) {
            JsonObject cache = requireObject(
                    caches.get(index),
                    "cacheContainers[" + index + "]"
            );
            String coordinateId = coordinateId(
                    coordinates,
                    coordinateIds,
                    usedIds,
                    requireText(cache, "dimension"),
                    requireObject(cache.get("position"), "cache position"),
                    requireText(cache, "name") + " position"
            );
            cache.addProperty("coordinateId", coordinateId);
        }
        migrated.add("coordinatePoints", coordinates);
        return migrated;
    }

    private static JsonObject migrateV3(JsonObject source) {
        JsonObject migrated = source.deepCopy();
        migrated.addProperty("schemaVersion", 4);
        if (!migrated.has("automationWorkflows")) {
            migrated.add("automationWorkflows", new JsonArray());
        }
        return migrated;
    }

    private static JsonObject migrateV4(JsonObject source) {
        JsonObject migrated = source.deepCopy();
        migrated.addProperty(
                "schemaVersion",
                StowlingConfig.CURRENT_SCHEMA_VERSION
        );
        addDefaultSettings(migrated, "pickupWorkflows");
        addDefaultSettings(migrated, "automationWorkflows");
        return migrated;
    }

    private static void addDefaultSettings(JsonObject root, String workflowField) {
        JsonElement workflowsElement = root.get(workflowField);
        if (workflowsElement == null || !workflowsElement.isJsonArray()) {
            return;
        }
        for (JsonElement workflowElement : workflowsElement.getAsJsonArray()) {
            if (workflowElement.isJsonObject()) {
                JsonObject workflow = workflowElement.getAsJsonObject();
                if (!workflow.has("settings")) {
                    workflow.add("settings", defaultTaskSettings());
                }
            }
        }
    }

    private static JsonObject defaultTaskSettings() {
        JsonObject settings = new JsonObject();
        settings.addProperty("priority", "MEDIUM");
        JsonObject trigger = new JsonObject();
        trigger.addProperty("kind", "MANUAL");
        trigger.addProperty("intervalSeconds", 0L);
        trigger.add("times", new JsonArray());
        trigger.addProperty("zoneId", "Asia/Shanghai");
        trigger.add("regionIds", new JsonArray());
        settings.add("trigger", trigger);
        settings.addProperty("autoStartOnRestart", false);
        JsonObject interruption = new JsonObject();
        interruption.addProperty("kind", "NONE");
        interruption.add("steps", new JsonArray());
        settings.add("interruption", interruption);
        return settings;
    }

    private static Set<String> pickupResourceIds(JsonObject root) {
        Set<String> ids = new LinkedHashSet<>();
        for (String field : List.of(
                "pickupRegions",
                "cacheContainers",
                "pickupWorkflows"
        )) {
            for (JsonElement element : requireArray(root, field)) {
                ids.add(requireText(requireObject(element, field), "id"));
            }
        }
        return ids;
    }

    private static void migrateShapeCoordinates(
            JsonObject shape,
            String resourceName,
            String dimension,
            JsonArray coordinates,
            Map<String, String> coordinateIds,
            Set<String> usedIds
    ) {
        String type = requireText(shape, "type");
        if ("SPHERE".equals(type)) {
            String id = coordinateId(
                    coordinates,
                    coordinateIds,
                    usedIds,
                    dimension,
                    requireObject(shape.get("center"), "sphere center"),
                    resourceName + " center"
            );
            shape.addProperty("centerCoordinateId", id);
            return;
        }
        String firstId = coordinateId(
                coordinates,
                coordinateIds,
                usedIds,
                dimension,
                requireObject(shape.get("firstCorner"), "first corner"),
                resourceName + " first corner"
        );
        String secondId = coordinateId(
                coordinates,
                coordinateIds,
                usedIds,
                dimension,
                requireObject(shape.get("secondCorner"), "second corner"),
                resourceName + " second corner"
        );
        shape.addProperty("firstCornerCoordinateId", firstId);
        shape.addProperty("secondCornerCoordinateId", secondId);
    }

    private static String coordinateId(
            JsonArray coordinates,
            Map<String, String> coordinateIds,
            Set<String> usedIds,
            String dimension,
            JsonObject position,
            String name
    ) {
        String key = coordinateLocationKey(dimension, position);
        String existing = coordinateIds.get(key);
        if (existing != null) {
            return existing;
        }
        String id = stableId(name, "coordinate-" + (coordinates.size() + 1), usedIds);
        JsonObject coordinate = new JsonObject();
        coordinate.addProperty("id", id);
        coordinate.addProperty("name", name);
        coordinate.addProperty("dimension", dimension);
        coordinate.add("position", position.deepCopy());
        coordinates.add(coordinate);
        coordinateIds.put(key, id);
        return id;
    }

    private static String coordinateLocationKey(String dimension, JsonObject position) {
        return dimension
                + '|'
                + requireField(position, "x").getAsInt()
                + '|'
                + requireField(position, "y").getAsInt()
                + '|'
                + requireField(position, "z").getAsInt();
    }

    private static void resolveCoordinateReferences(JsonObject root) {
        Map<String, JsonObject> coordinates = coordinateMap(root);
        JsonArray regions = requireArray(root, "pickupRegions");
        for (int index = 0; index < regions.size(); index++) {
            JsonObject region = requireObject(
                    regions.get(index),
                    "pickupRegions[" + index + "]"
            );
            String regionPath = "pickupRegions[" + index + "]";
            JsonObject standby = referencedCoordinate(
                    coordinates,
                    region,
                    "standbyCoordinateId",
                    regionPath
            );
            region.add("dimension", requireField(standby, "dimension").deepCopy());
            region.add("standbyPosition", requireField(standby, "position").deepCopy());
            resolveShapeReferences(coordinates, region, regionPath);
        }
        JsonArray caches = requireArray(root, "cacheContainers");
        for (int index = 0; index < caches.size(); index++) {
            JsonObject cache = requireObject(
                    caches.get(index),
                    "cacheContainers[" + index + "]"
            );
            String path = "cacheContainers[" + index + "]";
            JsonObject coordinate = referencedCoordinate(
                    coordinates,
                    cache,
                    "coordinateId",
                    path
            );
            cache.add("dimension", requireField(coordinate, "dimension").deepCopy());
            cache.add("position", requireField(coordinate, "position").deepCopy());
        }
    }

    private static Map<String, JsonObject> coordinateMap(JsonObject root) {
        Map<String, JsonObject> coordinates = new LinkedHashMap<>();
        JsonArray values = requireArray(root, "coordinatePoints");
        for (int index = 0; index < values.size(); index++) {
            JsonObject coordinate = requireObject(
                    values.get(index),
                    "coordinatePoints[" + index + "]"
            );
            coordinates.put(requireText(coordinate, "id"), coordinate);
        }
        return coordinates;
    }

    private static JsonObject referencedCoordinate(
            Map<String, JsonObject> coordinates,
            JsonObject resource,
            String field,
            String path
    ) {
        String id = requireText(resource, field);
        JsonObject coordinate = coordinates.get(id);
        if (coordinate == null) {
            throw new ConfigLoadException(
                    path + "." + field + " references unknown coordinate: " + id
            );
        }
        return coordinate;
    }

    private static void resolveShapeReferences(
            Map<String, JsonObject> coordinates,
            JsonObject region,
            String regionPath
    ) {
        JsonObject shape = requireObject(region.get("shape"), regionPath + ".shape");
        if ("SPHERE".equals(requireText(shape, "type"))) {
            JsonObject center = referencedCoordinate(
                    coordinates,
                    shape,
                    "centerCoordinateId",
                    regionPath + ".shape"
            );
            shape.add("center", requireField(center, "position").deepCopy());
            return;
        }
        JsonObject first = referencedCoordinate(
                coordinates,
                shape,
                "firstCornerCoordinateId",
                regionPath + ".shape"
        );
        JsonObject second = referencedCoordinate(
                coordinates,
                shape,
                "secondCornerCoordinateId",
                regionPath + ".shape"
        );
        shape.add("firstCorner", requireField(first, "position").deepCopy());
        shape.add("secondCorner", requireField(second, "position").deepCopy());
    }

    private static List<String> migrateSiteCaches(
            JsonArray sourceCaches,
            JsonArray migratedCaches,
            Map<String, String> cacheIdByLocation,
            Map<String, String> cacheTypeByLocation,
            Map<String, String> cacheDescriptorByLegacyId,
            Map<String, String> migratedCacheIds,
            Set<String> usedIds
    ) {
        List<String> siteCacheIds = new ArrayList<>();
        for (int index = 0; index < sourceCaches.size(); index++) {
            JsonObject cache = requireObject(
                    sourceCaches.get(index),
                    "cacheContainers[" + index + "]"
            );
            String oldId = requireText(cache, "id");
            String locationKey = cacheLocationKey(cache);
            String cacheType = requireText(cache, "type");
            String previousType = cacheTypeByLocation.putIfAbsent(locationKey, cacheType);
            if (previousType != null && !previousType.equals(cacheType)) {
                throw new ConfigLoadException(
                        "Legacy cache location has conflicting container types: "
                                + locationKey
                );
            }
            String descriptor = locationKey + '|' + cacheType;
            String previousDescriptor = cacheDescriptorByLegacyId.putIfAbsent(
                    oldId,
                    descriptor
            );
            if (previousDescriptor != null && !previousDescriptor.equals(descriptor)) {
                throw new ConfigLoadException(
                        "Legacy cache id points to conflicting containers: " + oldId
                );
            }
            String cacheId = cacheIdByLocation.get(locationKey);
            if (cacheId == null) {
                cacheId = stableId(oldId, "cache-" + (migratedCaches.size() + 1), usedIds);
                cacheIdByLocation.put(locationKey, cacheId);
                migratedCaches.add(createMigratedCache(cache, cacheId, oldId));
            }
            migratedCacheIds.putIfAbsent(oldId, cacheId);
            if (!siteCacheIds.contains(cacheId)) {
                siteCacheIds.add(cacheId);
            }
        }
        return List.copyOf(siteCacheIds);
    }

    private static JsonObject createMigratedCache(
            JsonObject source,
            String id,
            String name
    ) {
        JsonObject cache = new JsonObject();
        cache.addProperty("id", id);
        cache.addProperty("name", name);
        cache.add("dimension", requireField(source, "dimension").deepCopy());
        cache.add("position", requireField(source, "position").deepCopy());
        cache.add("type", requireField(source, "type").deepCopy());
        return cache;
    }

    private static JsonObject createMigratedWorkflow(
            String id,
            String name,
            String dimension,
            String regionId,
            List<String> cacheIds,
            JsonElement idleDelay
    ) {
        JsonObject workflow = new JsonObject();
        workflow.addProperty("id", id);
        workflow.addProperty("name", name);
        workflow.addProperty("dimension", dimension);
        JsonObject binding = new JsonObject();
        binding.addProperty("regionId", regionId);
        binding.addProperty("priority", 100);
        binding.add("idleDelay", idleDelay.deepCopy());
        JsonArray bindings = new JsonArray();
        bindings.add(binding);
        workflow.add("regions", bindings);
        JsonArray cacheIdArray = new JsonArray();
        cacheIds.forEach(cacheIdArray::add);
        workflow.add("cacheIds", cacheIdArray);
        workflow.addProperty("cachePolicy", "NEAREST_AVAILABLE");
        workflow.addProperty("classificationTiming", "AFTER_ROUND");
        workflow.addProperty("completionMode", "ALL_IDLE");
        workflow.add("completionIdleDelay", idleDelay.deepCopy());
        return workflow;
    }

    private static JsonArray migrateAuditSchedules(
            JsonArray schedules,
            Map<String, String> migratedCacheIds
    ) {
        JsonArray migrated = schedules.deepCopy();
        for (JsonElement scheduleElement : migrated) {
            if (!scheduleElement.isJsonObject()) {
                continue;
            }
            JsonElement idsElement = scheduleElement.getAsJsonObject().get("containerIds");
            if (idsElement == null || !idsElement.isJsonArray()) {
                continue;
            }
            JsonArray ids = idsElement.getAsJsonArray();
            for (int index = 0; index < ids.size(); index++) {
                String replacement = migratedCacheIds.get(ids.get(index).getAsString());
                if (replacement != null) {
                    ids.set(index, new com.google.gson.JsonPrimitive(replacement));
                }
            }
        }
        return migrated;
    }

    private static String cacheLocationKey(JsonObject cache) {
        JsonObject position = requireObject(
                requireField(cache, "position"),
                "cache position"
        );
        return requireText(cache, "dimension")
                + '|'
                + requireField(position, "x").getAsInt()
                + '|'
                + requireField(position, "y").getAsInt()
                + '|'
                + requireField(position, "z").getAsInt();
    }

    private static String stableId(
            String preferred,
            String fallback,
            Set<String> usedIds
    ) {
        String base = normalizeId(preferred);
        if (base.isEmpty()) {
            base = normalizeId(fallback);
        }
        if (base.isEmpty()) {
            base = "resource";
        }
        String candidate = truncateId(base);
        int suffix = 2;
        while (!usedIds.add(candidate)) {
            String suffixText = "-" + suffix;
            candidate = truncateId(base, 64 - suffixText.length()) + suffixText;
            suffix++;
        }
        return candidate;
    }

    private static String normalizeId(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("-+$", "");
        if (!SAFE_ID.matcher(normalized).matches()) {
            return truncateId(normalized);
        }
        return normalized;
    }

    private static String truncateId(String value) {
        return truncateId(value, 64);
    }

    private static String truncateId(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static JsonArray requireArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new ConfigLoadException(name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new ConfigLoadException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonElement requireField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new ConfigLoadException("Missing configuration field: " + name);
        }
        return value;
    }

    private static String requireText(JsonObject object, String name) {
        String value = requireField(object, name).getAsString().trim();
        if (value.isEmpty()) {
            throw new ConfigLoadException(name + " must not be blank");
        }
        return value;
    }

    private static JsonElement seconds(int value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    private static void copyRequired(JsonObject source, JsonObject target, String name) {
        target.add(name, requireField(source, name).deepCopy());
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
