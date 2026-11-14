package dev.stowling.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class Schema6Migration {
    private static final int LEGACY_DEFAULT_RADIUS = 8;
    private static final Set<String> DIRECT_PICKUP_KEYS = Set.of(
            "standbyCoordinateId",
            "firstCornerCoordinateId",
            "secondCornerCoordinateId",
            "cacheIds",
            "classificationTiming"
    );
    private static final List<String> RESOURCE_FIELDS = List.of(
            "coordinatePoints",
            "pickupRegions",
            "cacheContainers",
            "pickupWorkflows",
            "automationWorkflows"
    );

    private Schema6Migration() {
    }

    static JsonObject migrate(JsonObject source) {
        Objects.requireNonNull(source, "source");
        JsonObject root = source.deepCopy();
        CoordinateCatalog coordinates = new CoordinateCatalog(root);
        migrateAutomationSteps(root, coordinates);
        migrateInterruptionSteps(root, "pickupWorkflows", coordinates);
        migrateInterruptionSteps(root, "automationWorkflows", coordinates);
        root.addProperty("schemaVersion", 6);
        return root;
    }

    private static void migrateAutomationSteps(
            JsonObject root,
            CoordinateCatalog coordinates
    ) {
        JsonArray workflows = requireArray(root, "automationWorkflows");
        for (JsonElement workflowElement : workflows) {
            JsonObject workflow = requireObject(workflowElement, "automation workflow");
            migrateSteps(workflow.get("steps"), coordinates);
        }
    }

    private static void migrateInterruptionSteps(
            JsonObject root,
            String workflowField,
            CoordinateCatalog coordinates
    ) {
        JsonArray workflows = requireArray(root, workflowField);
        for (JsonElement workflowElement : workflows) {
            JsonObject workflow = requireObject(workflowElement, workflowField);
            JsonElement settingsElement = workflow.get("settings");
            if (settingsElement == null || !settingsElement.isJsonObject()) {
                continue;
            }
            JsonElement interruptionElement = settingsElement.getAsJsonObject()
                    .get("interruption");
            if (interruptionElement == null || !interruptionElement.isJsonObject()) {
                continue;
            }
            migrateSteps(
                    interruptionElement.getAsJsonObject().get("steps"),
                    coordinates
            );
        }
    }

    private static void migrateSteps(
            JsonElement stepsElement,
            CoordinateCatalog coordinates
    ) {
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            return;
        }
        for (JsonElement stepElement : stepsElement.getAsJsonArray()) {
            JsonObject step = requireObject(stepElement, "automation step");
            if (!"RUN_PICKUP".equals(optionalText(step, "type"))) {
                continue;
            }
            JsonObject parameters = requireObject(
                    step.get("parameters"),
                    "RUN_PICKUP parameters"
            );
            migratePickupStep(parameters, coordinates);
        }
    }

    private static void migratePickupStep(
            JsonObject parameters,
            CoordinateCatalog coordinates
    ) {
        String workflowId = optionalText(parameters, "workflowId");
        if (!workflowId.isEmpty()) {
            return;
        }
        String coordinateId = optionalText(parameters, "coordinateId");
        if (!coordinateId.isEmpty()) {
            int radius = legacyRadius(parameters);
            String migratedId = coordinates.ensureSphere(coordinateId, radius);
            parameters.addProperty("coordinateId", migratedId);
            parameters.remove("radius");
            return;
        }
        String regionId = optionalText(parameters, "regionId");
        if (regionId.isEmpty()
                || parameters.has("radius")
                || DIRECT_PICKUP_KEYS.stream().anyMatch(parameters::has)) {
            return;
        }
        String migratedId = coordinates.coordinateForRegion(regionId);
        parameters.remove("regionId");
        parameters.addProperty("coordinateId", migratedId);
    }

    private static int legacyRadius(JsonObject parameters) {
        JsonElement radiusElement = parameters.get("radius");
        if (radiusElement == null) {
            return LEGACY_DEFAULT_RADIUS;
        }
        try {
            int radius = Integer.parseInt(radiusElement.getAsString().trim());
            if (radius >= 1 && radius <= 128) {
                return radius;
            }
        } catch (NumberFormatException exception) {
            throw new ConfigLoadException(
                    "Legacy pickup radius must be an integer between 1 and 128",
                    exception
            );
        }
        throw new ConfigLoadException(
                "Legacy pickup radius must be an integer between 1 and 128"
        );
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

    private static String optionalText(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.getAsString().trim();
    }

    private static final class CoordinateCatalog {
        private final JsonArray coordinates;
        private final Map<String, JsonObject> byId = new LinkedHashMap<>();
        private final Map<String, JsonObject> regions = new LinkedHashMap<>();
        private final Map<String, String> regionCoordinateIds = new LinkedHashMap<>();
        private final Set<String> usedIds = new LinkedHashSet<>();

        private CoordinateCatalog(JsonObject root) {
            collectResourceIds(root);
            coordinates = requireArray(root, "coordinatePoints");
            for (JsonElement element : coordinates) {
                JsonObject coordinate = requireObject(element, "coordinate point");
                byId.put(requireText(coordinate, "id"), coordinate);
            }
            for (JsonElement element : requireArray(root, "pickupRegions")) {
                JsonObject region = requireObject(element, "pickup region");
                regions.put(requireText(region, "id"), region);
            }
        }

        private void collectResourceIds(JsonObject root) {
            for (String field : RESOURCE_FIELDS) {
                for (JsonElement element : requireArray(root, field)) {
                    usedIds.add(requireText(requireObject(element, field), "id"));
                }
            }
        }

        private String ensureSphere(String coordinateId, int radius) {
            JsonObject source = coordinate(coordinateId);
            String dimension = requireText(source, "dimension");
            JsonObject position = requireObject(
                    source.get("position"),
                    "coordinate position"
            );
            if ("POINT".equals(coordinateType(source))) {
                source.addProperty("type", "SPHERE");
                source.addProperty("radius", radius);
                source.remove("secondCorner");
                return coordinateId;
            }
            JsonObject matching = find("SPHERE", dimension, position, radius);
            if (matching != null) {
                return requireText(matching, "id");
            }
            return createCoordinate(
                    coordinateId + "-r" + radius,
                    optionalText(source, "name") + " radius " + radius,
                    dimension,
                    position,
                    "SPHERE",
                    radius,
                    null
            );
        }

        private String coordinateForRegion(String regionId) {
            String existingId = regionCoordinateIds.get(regionId);
            if (existingId != null) {
                return existingId;
            }
            JsonObject region = regions.get(regionId);
            if (region == null) {
                throw new ConfigLoadException(
                        "RUN_PICKUP references unknown region: " + regionId
                );
            }
            String dimension = requireText(region, "dimension");
            String name = requireText(region, "name");
            JsonObject shape = requireObject(region.get("shape"), "pickup region shape");
            String type = requireText(shape, "type");
            if ("SPHERE".equals(type)) {
                JsonObject center = coordinate(requireText(shape, "centerCoordinateId"));
                String id = createCoordinate(
                        regionId + "-pickup",
                        name + " pickup",
                        dimension,
                        requireObject(center.get("position"), "sphere center"),
                        "SPHERE",
                        requireField(shape, "radius").getAsInt(),
                        null
                );
                regionCoordinateIds.put(regionId, id);
                return id;
            }
            if (!"CUBOID".equals(type)) {
                throw new ConfigLoadException("Unsupported pickup region shape: " + type);
            }
            JsonObject first = coordinate(requireText(shape, "firstCornerCoordinateId"));
            JsonObject second = coordinate(requireText(shape, "secondCornerCoordinateId"));
            String id = createCoordinate(
                    regionId + "-pickup",
                    name + " pickup",
                    dimension,
                    requireObject(first.get("position"), "first corner"),
                    "AREA",
                    null,
                    requireObject(second.get("position"), "second corner")
            );
            regionCoordinateIds.put(regionId, id);
            return id;
        }

        private JsonObject coordinate(String id) {
            JsonObject coordinate = byId.get(id);
            if (coordinate == null) {
                throw new ConfigLoadException(
                        "RUN_PICKUP references unknown coordinate: " + id
                );
            }
            return coordinate;
        }

        private JsonObject find(
                String type,
                String dimension,
                JsonObject position,
                int radius
        ) {
            for (JsonObject coordinate : byId.values()) {
                if (type.equals(coordinateType(coordinate))
                        && dimension.equals(optionalText(coordinate, "dimension"))
                        && position.equals(coordinate.get("position"))
                        && coordinate.has("radius")
                        && coordinate.get("radius").getAsInt() == radius) {
                    return coordinate;
                }
            }
            return null;
        }

        private String createCoordinate(
                String preferredId,
                String name,
                String dimension,
                JsonObject position,
                String type,
                Integer radius,
                JsonObject secondCorner
        ) {
            String id = stableId(preferredId);
            JsonObject coordinate = new JsonObject();
            coordinate.addProperty("id", id);
            coordinate.addProperty("name", name);
            coordinate.addProperty("dimension", dimension);
            coordinate.add("position", position.deepCopy());
            coordinate.addProperty("type", type);
            if (radius != null) {
                coordinate.addProperty("radius", radius);
            }
            if (secondCorner != null) {
                coordinate.add("secondCorner", secondCorner.deepCopy());
            }
            coordinates.add(coordinate);
            byId.put(id, coordinate);
            return id;
        }

        private String stableId(String preferred) {
            String normalized = preferred.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9._-]+", "-")
                    .replaceAll("^[^a-z0-9]+", "")
                    .replaceAll("-+$", "");
            if (normalized.isEmpty()) {
                normalized = "coordinate-pickup";
            }
            String base = truncate(normalized);
            String candidate = base;
            int suffix = 2;
            while (!usedIds.add(candidate)) {
                String suffixText = "-" + suffix;
                candidate = truncate(base, 64 - suffixText.length()) + suffixText;
                suffix++;
            }
            return candidate;
        }

        private static String coordinateType(JsonObject coordinate) {
            String type = optionalText(coordinate, "type");
            return type.isEmpty() ? "POINT" : type.toUpperCase(Locale.ROOT);
        }

        private static String truncate(String value) {
            return truncate(value, 64);
        }

        private static String truncate(String value, int maximumLength) {
            return value.length() <= maximumLength
                    ? value
                    : value.substring(0, maximumLength);
        }
    }
}
