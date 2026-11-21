package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The runtime expands this layout category using the cache's actual stack limits. */
public final class UnstackableCategoryRule implements CategoryRule {
    public static final String ID = "UNSTACKABLE_ITEMS";
    public static final String CATEGORY = "stowling:unstackable_items";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(ID, "不可堆叠物品（排除潜影盒）",
                "读取展示框样品，按原版红石分类分组收纳不可堆叠物品，排除潜影盒。");
    }

    @Override
    public CategoryRuleResult resolve(List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers) {
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("储存区没有可用容器");
        }
        Map<String, List<ContainerTarget>> categories = new LinkedHashMap<>();
        boolean hasAttachedSample = false;
        for (ContainerTarget target : storageContainers) {
            List<CategoryMarker> attached = markers.stream().filter(this::accepts)
                    .filter(marker -> target.entrances().contains(marker.position()))
                    .toList();
            hasAttachedSample |= !attached.isEmpty();
            attached.stream().map(marker -> familyCategory(marker.itemId()))
                    .filter(family -> !CATEGORY.equals(family)).distinct()
                    .forEach(family -> categories.computeIfAbsent(family,
                            ignored -> new ArrayList<>()).add(target));
        }
        return CategoryRuleResult.success(hasAttachedSample
                ? categories : Map.of(CATEGORY, storageContainers));
    }

    public static CategoryRuleResult resolveMarkerArea(List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers) {
        List<CategoryMarker> families = markers.stream()
                .filter(marker -> marker.source() == CategoryMarkerSource.ITEM_FRAME)
                .map(marker -> new CategoryMarker(marker.position(), familyCategory(marker.itemId()),
                        marker.source(), marker.slotIndex()))
                .filter(marker -> !CATEGORY.equals(marker.itemId()))
                .toList();
        if (families.isEmpty()) {
            // An unused slice must not claim inventory intended for its neighbors.
            return CategoryRuleResult.success(Map.of());
        }
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("展示框对应的储存区没有可用容器");
        }
        String first = families.getFirst().itemId();
        if (families.stream().allMatch(marker -> first.equals(marker.itemId()))) {
            return CategoryRuleResult.success(Map.of(first, storageContainers));
        }
        return new DirectBlockIdCategoryRule().resolve(families, storageContainers);
    }

    public static boolean isLayoutCategory(String id) {
        return CATEGORY.equals(id) || id.startsWith(CATEGORY + "/");
    }

    public static String familyCategory(String inventoryKey) {
        String id = ItemIdentity.base(inventoryKey);
        if (!id.startsWith("minecraft:")) {
            return CATEGORY;
        }
        String path = id.substring("minecraft:".length());
        String group = switch (path) {
            case "potion", "splash_potion", "lingering_potion" -> "potions";
            case "bamboo_raft", "bamboo_chest_raft" -> "boats";
            case "minecart" -> "minecarts";
            case "enchanted_book", "shears", "flint_and_steel", "powder_snow_bucket",
                    "lava_bucket", "water_bucket", "sulfur_cube_bucket" -> path;
            default -> {
                if (path.endsWith("_boat")) {
                    yield "boats";
                }
                if (path.endsWith("_minecart")) {
                    yield "minecarts";
                }
                if (path.startsWith("music_disc_")) {
                    yield "music_discs";
                }
                if (path.endsWith("_helmet") || path.endsWith("_chestplate")
                        || path.endsWith("_leggings") || path.endsWith("_boots")) {
                    yield "armor";
                }
                yield "";
            }
        };
        return group.isEmpty() ? CATEGORY : CATEGORY + "/" + group;
    }

    @Override
    public boolean accepts(CategoryMarker marker) {
        return marker.source() == CategoryMarkerSource.ITEM_FRAME;
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }
}
