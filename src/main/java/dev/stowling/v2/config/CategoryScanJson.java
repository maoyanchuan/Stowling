package dev.stowling.v2.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** One strict shape for scan drafts in HTTP and persisted configuration. */
public final class CategoryScanJson {
    private CategoryScanJson() {
    }

    public static void validate(JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        var keys = Set.of("area", "dimension", "axis", "step", "templateIndex",
                "sliceCount", "templates", "blocks", "slices");
        var allowed = new java.util.HashSet<>(keys);
        allowed.add("canvasSize");
        allowed.add("markerAnchors");
        var scan = CategoryOptionsJson.object(value, allowed);
        for (String key : keys) if (!present(scan.get(key))) fail();
        if (present(scan.get("markerAnchors"))) {
            if (!scan.get("markerAnchors").isJsonObject()) fail();
            var anchors = scan.getAsJsonObject("markerAnchors");
            if (anchors.size() > 256) fail();
            for (var entry : anchors.entrySet()) {
                try {
                    java.util.UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException exception) {
                    throw new V2ValidationException("逻辑展示位组标识无效", exception);
                }
                if (!present(entry.getValue()) || !entry.getValue().isJsonArray()) fail();
                var positions = entry.getValue().getAsJsonArray();
                if (positions.isEmpty() || positions.size() > 64) fail();
                for (var position : positions) {
                    if (!present(position)) fail();
                    CategoryOptionsJson.position(position, false);
                }
            }
        }
        if (present(scan.get("canvasSize"))) {
            var size = required(scan.get("canvasSize"), Set.of("columns", "rows"));
            number(size, "columns");
            number(size, "rows");
        }
        area(scan.get("area"));
        for (String key : Set.of("step", "templateIndex", "sliceCount")) number(scan, key);
        for (String key : Set.of("axis", "dimension")) text(scan, key);
        for (var entry : array(scan, "blocks", 4096)) {
            var block = CategoryOptionsJson.object(entry, Set.of("position", "blockId", "kind",
                    "facing", "partner", "turns", "upper"));
            CategoryOptionsJson.position(block.get("position"), false);
            if (!block.has("position")) fail();
            CategoryOptionsJson.position(block.get("partner"), false);
            for (String key : Set.of("blockId", "kind", "facing")) text(block, key);
            CategoryOptionsJson.integer(block.get("turns"));
            CategoryOptionsJson.primitive(block.get("upper"), "boolean");
        }
        for (var entry : array(scan, "slices", 256)) {
            var slice = required(entry, Set.of("index", "origin", "reason"));
            number(slice, "index");
            CategoryOptionsJson.position(slice.get("origin"), false);
            text(slice, "reason");
        }
        for (var entry : array(scan, "templates", 256)) {
            var binding = CategoryOptionsJson.object(entry, Set.of("id", "name", "markerArea",
                    "storageArea", "ruleId", "ignoredMarkerBlockIds", "containerOverrides",
                    "standingArea", "storagePolicy", "wholeStorage", "optionsEnabled",
                    "categoryOptions", "standingPlan"));
            text(binding, "id");
            text(binding, "ruleId");
            CategoryOptionsJson.primitive(binding.get("name"), "string");
            area(binding.get("markerArea"));
            area(binding.get("storageArea"));
            if (present(binding.get("standingArea"))) area(binding.get("standingArea"));
            CategoryOptionsJson.primitive(binding.get("optionsEnabled"), "boolean");
            CategoryOptionsJson.primitive(binding.get("wholeStorage"), "boolean");
            CategoryOptionsJson.validate(binding.get("categoryOptions"));
            CategoryStandingJson.validate(binding.get("standingPlan"));
            var ids = binding.get("ignoredMarkerBlockIds");
            if (present(ids)) {
                if (!ids.isJsonArray()) fail();
                for (var id : ids.getAsJsonArray()) CategoryOptionsJson.primitive(id, "string");
            }
            var policy = binding.get("storagePolicy");
            if (present(policy)) {
                var p = CategoryOptionsJson.object(policy, Set.of("mode", "loosePercent",
                        "allowPartialBoxes", "packingArea", "refillLoose", "looseSlots",
                        "partialBoxDestinationRef"));
                CategoryOptionsJson.primitive(p.get("mode"), "string");
                CategoryOptionsJson.primitive(p.get("partialBoxDestinationRef"), "string");
                CategoryOptionsJson.integer(p.get("loosePercent"));
                CategoryOptionsJson.integer(p.get("looseSlots"));
                CategoryOptionsJson.primitive(p.get("allowPartialBoxes"), "boolean");
                CategoryOptionsJson.primitive(p.get("refillLoose"), "boolean");
                if (present(p.get("packingArea"))) area(p.get("packingArea"));
            }
            var overrides = binding.get("containerOverrides");
            if (present(overrides)) {
                if (!overrides.isJsonArray()) fail();
                for (var raw : overrides.getAsJsonArray()) {
                    var override = required(raw, Set.of("id", "storagePosition", "mode", "itemIds"));
                    text(override, "id");
                    text(override, "mode");
                    CategoryOptionsJson.position(override.get("storagePosition"), false);
                    for (var id : array(override, "itemIds", 4096)) {
                        CategoryOptionsJson.primitive(id, "string");
                    }
                }
            }
        }
    }

    public static void area(JsonElement value) {
        var keys = Set.of("firstX", "firstY", "firstZ", "secondX", "secondY", "secondZ");
        var bounds = required(value, keys);
        for (String key : keys) number(bounds, key);
    }

    private static JsonObject required(JsonElement value, Set<String> keys) {
        var object = CategoryOptionsJson.object(value, keys);
        if (!object.keySet().containsAll(keys)) fail();
        for (String key : keys) if (!present(object.get(key))) fail();
        return object;
    }

    private static com.google.gson.JsonArray array(JsonObject object, String key, int limit) {
        var value = object.get(key);
        if (!present(value) || !value.isJsonArray() || value.getAsJsonArray().size() > limit) fail();
        return value.getAsJsonArray();
    }

    private static void number(JsonObject object, String key) {
        if (!present(object.get(key))) fail();
        CategoryOptionsJson.integer(object.get(key));
    }

    private static void text(JsonObject object, String key) {
        if (!present(object.get(key))) fail();
        CategoryOptionsJson.primitive(object.get(key), "string");
    }

    private static boolean present(JsonElement value) {
        return value != null && !value.isJsonNull();
    }

    private static void fail() {
        throw new V2ValidationException("扫描配置字段不完整或类型无效");
    }
}
