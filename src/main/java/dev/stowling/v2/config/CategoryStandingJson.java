package dev.stowling.v2.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** The same strict JSON shape validation for API requests and configuration files. */
public final class CategoryStandingJson {
    private CategoryStandingJson() {
    }

    public static void validate(JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        JsonObject plan = CategoryOptionsJson.object(value, Set.of("enabled", "points"));
        CategoryOptionsJson.primitive(plan.get("enabled"), "boolean");
        JsonElement points = plan.get("points");
        if (points == null || !points.isJsonArray()) {
            throw new V2ValidationException("站位列表必须使用数组");
        }
        for (JsonElement entry : points.getAsJsonArray()) {
            JsonObject point = CategoryOptionsJson.object(entry,
                    Set.of("position", "markerArea", "storageArea", "expanded",
                            "subregionsEnabled", "standingId"));
            CategoryOptionsJson.position(point.get("position"), true);
            CategoryOptionsJson.primitive(point.get("expanded"), "boolean");
            CategoryOptionsJson.primitive(point.get("subregionsEnabled"), "boolean");
            CategoryOptionsJson.primitive(point.get("standingId"), "string");
            for (String key : Set.of("markerArea", "storageArea")) {
                JsonElement raw = point.get(key);
                if (raw == null || raw.isJsonNull()) continue;
                Set<String> axes = Set.of("firstX", "firstY", "firstZ",
                        "secondX", "secondY", "secondZ");
                JsonObject area = CategoryOptionsJson.object(raw, axes);
                if (!area.keySet().containsAll(axes)) {
                    throw new V2ValidationException("请填写完整的子区坐标");
                }
                for (String axis : axes) {
                    JsonElement number = area.get(axis);
                    if (number == null || number.isJsonNull()) {
                        throw new V2ValidationException("请填写完整的子区坐标");
                    }
                    CategoryOptionsJson.integer(number);
                }
            }
        }
    }
}
