package dev.stowling.automation;

import java.util.Set;

/** Exact inventory keys retain a readable base ID and, for boxes, their category. */
public final class ItemIdentity {
    private ItemIdentity() {
    }

    public static String base(String key) {
        int suffix = key.indexOf('#');
        return suffix < 0 ? key : key.substring(0, suffix);
    }

    public static String category(String key) {
        int suffix = key.indexOf('@');
        return suffix < 0 ? base(key) : key.substring(suffix + 1);
    }

    public static boolean isBox(String key) {
        String base = base(key);
        return base.equals("minecraft:shulker_box") || base.endsWith("_shulker_box");
    }

    public static boolean matches(String key, Set<String> categories) {
        return categories.contains(key) || categories.contains(category(key));
    }

}
