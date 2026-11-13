package dev.stowling.api;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.stowling.control.AuditRequest;
import dev.stowling.control.ConfigCoordinateUpdate;
import dev.stowling.control.ConfigCoordinatesUpdateRequest;
import dev.stowling.control.PickupRequest;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class StrictRequestParser {
    PickupRequest parsePickup(String body) throws InvalidRequestException {
        try (JsonReader reader = reader(body)) {
            Set<String> fields = new HashSet<>();
            String siteId = null;
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "JSON body must be an object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                require(fields.add(name), "Duplicate JSON field: " + name);
                require("siteId".equals(name), "Unknown JSON field: " + name);
                siteId = readString(reader, "siteId");
            }
            reader.endObject();
            requireEnd(reader);
            require(siteId != null, "Missing JSON field: siteId");
            return new PickupRequest(siteId);
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
