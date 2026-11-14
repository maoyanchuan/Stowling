package dev.stowling.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.SpherePickupShape;
import java.time.Duration;
import java.time.Instant;

final class ApiJson {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(
            Instant.class,
            (com.google.gson.JsonSerializer<Instant>) (value, type, context) ->
                    new JsonPrimitive(value.toString())
    )
            .registerTypeAdapter(
            Duration.class,
            (com.google.gson.JsonSerializer<Duration>) (value, type, context) ->
                    new JsonPrimitive(value.toSeconds())
    )
            .registerTypeHierarchyAdapter(
            PickupShape.class,
            (com.google.gson.JsonSerializer<PickupShape>) (value, type, context) -> {
                JsonObject json = new JsonObject();
                json.addProperty("type", value.type().name());
                if (value instanceof SpherePickupShape sphere) {
                    json.addProperty(
                            "centerCoordinateId",
                            sphere.centerCoordinateId()
                    );
                    json.add("center", context.serialize(sphere.center()));
                    json.addProperty("radius", sphere.radius());
                } else {
                    CuboidPickupShape cuboid = (CuboidPickupShape) value;
                    json.addProperty(
                            "firstCornerCoordinateId",
                            cuboid.firstCornerCoordinateId()
                    );
                    json.addProperty(
                            "secondCornerCoordinateId",
                            cuboid.secondCornerCoordinateId()
                    );
                    json.add("firstCorner", context.serialize(cuboid.firstCorner()));
                    json.add("secondCorner", context.serialize(cuboid.secondCorner()));
                }
                return json;
            }
    )
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private ApiJson() {
    }

    static String encode(Object value) {
        return GSON.toJson(value);
    }
}
