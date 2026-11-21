package dev.stowling.v2.model;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class MaterialRequirements {
    public static final String MODULE = "COLLECT_SCHEMATIC_MATERIALS";
    public static final String PARAMETER = "materials";
    public static final String BOXED_DELIVERY = "boxedDelivery";
    public static final String SINGLE_MATERIAL_BOXES = "singleMaterialBoxes";
    public static final String ICE_INSTEAD_OF_WATER = "iceInsteadOfWater";
    public static final String SKIP_WATER_BUCKETS = "skipWaterBuckets";
    public static final String SKIP_LAVA = "skipLava";
    public static final String WATER_BOTTLE = "minecraft:potion[water]";
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_LENGTH = 56_000;

    private MaterialRequirements() {
    }

    public static Map<String, Integer> forCollection(Map<String, String> parameters) {
        Map<String, Integer> original = parse(parameters.get(PARAMETER));
        boolean ice = Boolean.parseBoolean(parameters.get(ICE_INSTEAD_OF_WATER));
        boolean skipWater = Boolean.parseBoolean(parameters.get(SKIP_WATER_BUCKETS));
        // Older tasks used a single setting for the ice substitution and the lava exclusion.
        boolean skipLava = Boolean.parseBoolean(
                parameters.getOrDefault(SKIP_LAVA, Boolean.toString(ice)));
        if (ice && skipWater) {
            throw new IllegalArgumentException("用普通冰代替水桶和跳过水桶不能同时开启");
        }
        if (!ice && !skipWater && !skipLava) {
            return original;
        }
        Map<String, Integer> requested = new LinkedHashMap<>(original);
        Integer waterBuckets = ice || skipWater ? requested.remove("minecraft:water_bucket") : null;
        if (skipLava) {
            requested.remove("minecraft:lava_bucket");
        }
        if (ice && waterBuckets != null) {
            try {
                requested.merge("minecraft:ice", waterBuckets, Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("替换后的普通冰数量过大", exception);
            }
        }
        return java.util.Collections.unmodifiableMap(requested);
    }

    public static int reserveBoxes(Map<String, Integer> remaining, Map<String, Integer> contents,
            int available) {
        if (contents.isEmpty()) {
            return 0;
        }
        int count = available;
        for (var entry : contents.entrySet()) {
            count = Math.min(count, remaining.getOrDefault(entry.getKey(), 0) / entry.getValue());
        }
        int reserved = count;
        contents.forEach((material, amount) -> remaining.computeIfPresent(material,
                (ignored, left) -> left - amount * reserved));
        return reserved;
    }

    public static boolean canDeliverBox(String material, Map<String, Integer> remaining,
            Map<String, Integer> contents, boolean full, boolean hasLooseOrPartial) {
        if (contents.size() != 1 || !contents.containsKey(material)) {
            return false;
        }
        int count = contents.get(material);
        int needed = remaining.getOrDefault(material, 0);
        return full ? count <= needed : !hasLooseOrPartial && count == needed;
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
