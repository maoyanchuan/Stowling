package dev.stowling.v2.model;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class MaterialRequirements {
    public static final String MODULE = "COLLECT_SCHEMATIC_MATERIALS";
    public static final String PARAMETER = "materials";
    public static final String WATER_BOTTLE = "minecraft:potion[water]";
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_LENGTH = 56_000;

    private MaterialRequirements() {
    }

    public static Map<String, Integer> parse(String value) {
        try {
            if (value == null || value.length() > MAX_LENGTH) {
                throw new IllegalArgumentException();
            }
            var object = JsonParser.parseString(value).getAsJsonObject();
            Map<String, Integer> items = new LinkedHashMap<>();
            for (var entry : object.entrySet()) {
                String id = entry.getKey();
                var count = entry.getValue().getAsJsonPrimitive();
                if ((!ITEM_ID.matcher(id).matches() && !WATER_BOTTLE.equals(id))
                        || !count.isNumber() || count.getAsBigDecimal().intValueExact() < 1) {
                    throw new IllegalArgumentException();
                }
                items.put(id, count.getAsInt());
            }
            if (!items.isEmpty()) {
                return java.util.Collections.unmodifiableMap(items);
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("投影材料清单无效或过大");
        }
        throw new IllegalArgumentException("投影材料清单为空");
    }
}
