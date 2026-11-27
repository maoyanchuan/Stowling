package dev.stowling.v2.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;

/** Consumes retired single-position options once, before strict API/config parsing. */
public final class CategoryStandingMigration {
    private CategoryStandingMigration() {
    }

    public static void migrate(JsonObject zone) {
        JsonObject global = options(zone);
        JsonElement raw = zone.get("bindings");
        if (raw != null && raw.isJsonArray()) {
            for (JsonElement element : raw.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject binding = element.getAsJsonObject();
                JsonObject local = options(binding);
                JsonElement independent = binding.get("optionsEnabled");
                boolean own = independent != null && independent.isJsonPrimitive()
                        && independent.getAsJsonPrimitive().isBoolean()
                        && independent.getAsBoolean();
                migrateBinding(binding, own ? local : global);
            }
            for (JsonElement element : raw.getAsJsonArray()) {
                if (element.isJsonObject()) clean(options(element.getAsJsonObject()));
            }
        }
        clean(global);
    }

    private static JsonObject options(JsonObject parent) {
        JsonElement value = parent.get("categoryOptions");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static void clean(JsonObject options) {
        if (options == null) return;
        options.remove("specifyStanding");
        options.remove("standingPosition");
    }

    private static void migrateBinding(JsonObject binding, JsonObject options) {
        JsonElement active = options == null ? null : options.get("specifyStanding");
        JsonElement position = options == null ? null : options.get("standingPosition");
        CategoryOptionsJson.primitive(active, "boolean");
        CategoryOptionsJson.position(position, false);
        JsonElement previous = binding.get("standingArea");
        JsonObject feet = previous != null && previous.isJsonObject()
                ? previous.getAsJsonObject() : null;
        boolean explicit = active != null && !active.isJsonNull();
        boolean enabled = explicit ? active.getAsBoolean() : feet != null;
        if (explicit && enabled) {
            if (position == null || !position.isJsonObject()) {
                throw new V2ValidationException("请填写旧站立脚下方块坐标");
            }
            JsonObject support = position.getAsJsonObject();
            int y = shifted(support.get("y").getAsInt(), 1);
            JsonObject repaired = new JsonObject();
            for (String corner : Set.of("first", "second")) {
                repaired.addProperty(corner + "X", feet == null ? support.get("x").getAsInt()
                        : feet.get(corner + "X").getAsInt());
                repaired.addProperty(corner + "Y", y);
                repaired.addProperty(corner + "Z", feet == null ? support.get("z").getAsInt()
                        : feet.get(corner + "Z").getAsInt());
            }
            binding.add("standingArea", repaired);
            feet = repaired;
        }
        JsonElement current = binding.get("standingPlan");
        if (current != null && !current.isJsonNull()) return;
        if (!explicit && feet == null && (position == null || position.isJsonNull())) return;
        JsonObject plan = new JsonObject();
        plan.addProperty("enabled", enabled);
        JsonArray points = new JsonArray();
        JsonObject support = null;
        if (enabled && feet != null) {
            support = new JsonObject();
            support.addProperty("x", feet.get("firstX").getAsInt());
            support.addProperty("y", shifted(feet.get("firstY").getAsInt(), -1));
            support.addProperty("z", feet.get("firstZ").getAsInt());
        } else if (position != null && position.isJsonObject()) {
            support = position.getAsJsonObject().deepCopy();
        }
        if (support != null) {
            JsonObject point = new JsonObject();
            point.add("position", support);
            point.addProperty("subregionsEnabled", false);
            point.addProperty("expanded", false);
            String identity = binding.has("id") ? binding.get("id").getAsString()
                    : UUID.randomUUID().toString();
            point.addProperty("standingId", "migrated-standing:" + identity);
            points.add(point);
        }
        plan.add("points", points);
        binding.add("standingPlan", plan);
    }

    private static int shifted(int coordinate, int delta) {
        try {
            return Math.addExact(coordinate, delta);
        } catch (ArithmeticException exception) {
            throw new V2ValidationException("旧站位高度超出整数坐标范围", exception);
        }
    }
}
