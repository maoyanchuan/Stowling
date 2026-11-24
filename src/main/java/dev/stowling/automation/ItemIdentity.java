package dev.stowling.automation;

import java.util.Set;

/** Exact inventory keys retain a readable base ID and an optional routing category. */
public final class ItemIdentity {
    public static final String MIXED_BOX_CATEGORY = "stowling:mixed_contents";
    public static final String NAMED_ITEM_CATEGORY = "stowling:named_item";

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

    public static boolean isMixedBox(String key) {
        return isBox(key) && MIXED_BOX_CATEGORY.equals(category(key));
    }

    public static boolean isEmptyBox(String key) {
        return isBox(key) && key.indexOf('@') < 0;
    }

    public static String boxContentsCategory(Set<String> itemIds) {
        Set<String> categories = itemIds.stream().map(ItemIdentity::base)
                .collect(java.util.stream.Collectors.toSet());
        return categories.size() == 1 ? categories.iterator().next()
                : categories.isEmpty() ? "" : MIXED_BOX_CATEGORY;
    }

    public static boolean matches(String key, Set<String> categories) {
        return categories.contains(key) || categories.contains(category(key))
                || categories.contains(exact(key));
    }

    public static String exact(String key) {
        return "=" + key;
    }

}
