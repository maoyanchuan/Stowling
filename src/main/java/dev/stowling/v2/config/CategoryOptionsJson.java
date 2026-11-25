package dev.stowling.v2.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Shared request/config shape validation; inactive rules remain editable drafts. */
public final class CategoryOptionsJson {
    private CategoryOptionsJson() {
    }

    public static void validate(JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        JsonObject options = object(value, Set.of("scopeMode", "recognitionMode", "storageMethod",
                "noCacheLocation", "cacheDestination", "ignoreSupportBlock", "supportBlock",
                "specifyStanding", "standingPosition", "bulkStacks", "ignoreMarkerPositions",
                "ignoredMarkerPositions", "ignoreRepeatedBlocks", "multiItemBindingsEnabled",
                "multiItemBindings", "unstackableOverflowRef"));
        for (String key : Set.of("ignoreSupportBlock", "specifyStanding", "ignoreMarkerPositions",
                "ignoreRepeatedBlocks", "multiItemBindingsEnabled")) {
            primitive(options.get(key), "boolean");
        }
        for (String key : Set.of("scopeMode", "recognitionMode", "storageMethod",
                "noCacheLocation", "cacheDestination", "supportBlock", "unstackableOverflowRef")) {
            primitive(options.get(key), "string");
        }
        integer(options.get("bulkStacks"));
        position(options.get("standingPosition"), false);
        JsonElement positions = options.get("ignoredMarkerPositions");
        if (present(positions)) {
            if (!positions.isJsonArray()) fail();
            for (JsonElement point : positions.getAsJsonArray()) {
                if (!present(point)) fail();
                position(point, true);
            }
        }
        JsonElement bindings = options.get("multiItemBindings");
        if (!present(bindings)) return;
        if (!bindings.isJsonArray()) fail();
        for (JsonElement item : bindings.getAsJsonArray()) {
            JsonObject rule = object(item,
                    Set.of("name", "targetType", "markerId", "position", "itemIds", "expanded"));
            for (String key : Set.of("name", "targetType", "markerId", "itemIds")) {
                primitive(rule.get(key), "string");
            }
            primitive(rule.get("expanded"), "boolean");
            position(rule.get("position"), true);
        }
    }

    private static JsonObject object(JsonElement value, Set<String> fields) {
        if (!present(value) || !value.isJsonObject()) {
            throw new V2ValidationException("分类区设置必须使用对象");
        }
        JsonObject result = value.getAsJsonObject();
        if (!fields.containsAll(result.keySet())) {
            throw new V2ValidationException("分类区设置包含未知字段");
        }
        return result;
    }

    private static void position(JsonElement value, boolean draft) {
        if (!present(value)) return;
        JsonObject point = object(value, Set.of("x", "y", "z"));
        for (String axis : Set.of("x", "y", "z")) {
            if (!draft && !present(point.get(axis))) fail();
            integer(point.get(axis));
        }
    }

    private static void integer(JsonElement value) {
        if (!present(value)) return;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) fail();
        try {
            value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new V2ValidationException("分类区数值必须使用 32 位整数", exception);
        }
    }

    private static void primitive(JsonElement value, String type) {
        if (!present(value)) return;
        if (!value.isJsonPrimitive()) fail();
        var primitive = value.getAsJsonPrimitive();
        if ("boolean".equals(type) ? !primitive.isBoolean() : !primitive.isString()) fail();
    }

    private static boolean present(JsonElement value) {
        return value != null && !value.isJsonNull();
    }

    private static void fail() {
        throw new V2ValidationException("分类区设置字段类型无效，请检查规则与整数坐标");
    }
}
