package dev.stowling.config;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.PickupShapeType;
import dev.stowling.domain.SpherePickupShape;
import java.lang.reflect.Type;

final class PickupShapeJsonAdapter
        implements JsonSerializer<PickupShape>, JsonDeserializer<PickupShape> {
    @Override
    public JsonElement serialize(
            PickupShape shape,
            Type type,
            JsonSerializationContext context
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("type", shape.type().name());
        if (shape instanceof SpherePickupShape sphere) {
            json.add("center", context.serialize(sphere.center()));
            json.addProperty("radius", sphere.radius());
            return json;
        }
        CuboidPickupShape cuboid = (CuboidPickupShape) shape;
        json.add("firstCorner", context.serialize(cuboid.firstCorner()));
        json.add("secondCorner", context.serialize(cuboid.secondCorner()));
        return json;
    }

    @Override
    public PickupShape deserialize(
            JsonElement element,
            Type type,
            JsonDeserializationContext context
    ) throws JsonParseException {
        if (!element.isJsonObject()) {
            throw new JsonParseException("pickup shape must be an object");
        }
        JsonObject json = element.getAsJsonObject();
        PickupShapeType shapeType = context.deserialize(
                requireField(json, "type"),
                PickupShapeType.class
        );
        if (shapeType == PickupShapeType.SPHERE) {
            BlockPosition center = context.deserialize(
                    requireField(json, "center"),
                    BlockPosition.class
            );
            int radius = requireField(json, "radius").getAsInt();
            return new SpherePickupShape(center, radius);
        }
        BlockPosition firstCorner = context.deserialize(
                requireField(json, "firstCorner"),
                BlockPosition.class
        );
        BlockPosition secondCorner = context.deserialize(
                requireField(json, "secondCorner"),
                BlockPosition.class
        );
        return new CuboidPickupShape(firstCorner, secondCorner);
    }

    private static JsonElement requireField(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException("missing pickup shape field: " + name);
        }
        return value;
    }
}
