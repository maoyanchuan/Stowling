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
import dev.stowling.control.ConfigCoordinateUpdate;
import dev.stowling.control.ConfigCoordinatesUpdateRequest;
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
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class StrictRequestParser {
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

    PickupConfigUpdateRequest parsePickupConfigUpdate(String body)
            throws InvalidRequestException {
        try {
            JsonObject root = readStrictObject(body);
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
        } catch (IOException | IllegalStateException | IllegalArgumentException exception) {
            throw malformed(exception);
        }
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

    private static List<PickupRegion> pickupRegions(JsonArray values) throws IOException {
        List<PickupRegion> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(values.get(index), "pickupRegions[" + index + "]");
            requireFields(
                    value,
                    Set.of("id", "name", "dimension", "standbyPosition", "shape"),
                    Set.of("id", "name", "dimension", "standbyPosition", "shape")
            );
            result.add(new PickupRegion(
                    string(value, "id", 64),
                    string(value, "name", 256),
                    string(value, "dimension", 128),
                    position(object(value.get("standbyPosition"), "standbyPosition")),
                    shape(object(value.get("shape"), "shape"))
            ));
        }
        return List.copyOf(result);
    }

    private static List<NamedCache> namedCaches(JsonArray values) throws IOException {
        List<NamedCache> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject value = object(values.get(index), "cacheContainers[" + index + "]");
            requireFields(
                    value,
                    Set.of("id", "name", "dimension", "position", "type"),
                    Set.of("id", "name", "dimension", "position", "type")
            );
            result.add(new NamedCache(
                    string(value, "id", 64),
                    string(value, "name", 256),
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
            JsonObject value = object(values.get(index), "pickupWorkflows[" + index + "]");
            requireFields(
                    value,
                    Set.of(
                    "id",
                    "name",
                    "dimension",
                    "regions",
                    "cacheIds",
                    "cachePolicy",
                    "classificationTiming",
                    "completionMode",
                    "completionIdleDelay"
            ),
                    Set.of(
                    "id",
                    "name",
                    "dimension",
                    "regions",
                    "cacheIds",
                    "cachePolicy",
                    "classificationTiming",
                    "completionMode",
                    "completionIdleDelay"
            )
            );
            result.add(new PickupWorkflow(
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
                    Duration.ofSeconds(nonNegativeLong(value, "completionIdleDelay"))
            ));
        }
        return List.copyOf(result);
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
            requireFields(
                    value,
                    Set.of("type", "center", "radius"),
                    Set.of("type", "center", "radius")
            );
            return new SpherePickupShape(
                    position(object(value.get("center"), "center")),
                    integer(value, "radius")
            );
        }
        if ("CUBOID".equals(type)) {
            requireFields(
                    value,
                    Set.of("type", "firstCorner", "secondCorner"),
                    Set.of("type", "firstCorner", "secondCorner")
            );
            return new CuboidPickupShape(
                    position(object(value.get("firstCorner"), "firstCorner")),
                    position(object(value.get("secondCorner"), "secondCorner"))
            );
        }
        throw new IOException("Unknown pickup shape type: " + type);
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
