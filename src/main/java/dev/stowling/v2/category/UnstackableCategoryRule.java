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
                "读取分类物识别区的展示框，按样品类型收纳不可堆叠物品，排除潜影盒。");
    }

    @Override
    public CategoryRuleResult resolve(List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers) {
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("储存区没有可用容器");
        }
        Map<String, List<ContainerTarget>> categories = new LinkedHashMap<>();
        for (ContainerTarget target : storageContainers) {
            markers.stream().filter(this::accepts)
                    .filter(marker -> target.entrances().contains(marker.position()))
                    .map(CategoryMarker::itemId).distinct()
                    .forEach(id -> categories.computeIfAbsent(familyCategory(id),
                            ignored -> new ArrayList<>()).add(target));
        }
        return CategoryRuleResult.success(categories.isEmpty()
                ? Map.of(CATEGORY, storageContainers) : categories);
    }

    public static CategoryRuleResult resolveMarkerArea(List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers) {
        List<CategoryMarker> families = markers.stream()
                .filter(marker -> marker.source() == CategoryMarkerSource.ITEM_FRAME)
                .map(marker -> new CategoryMarker(marker.position(), familyCategory(marker.itemId()),
                        marker.source(), marker.slotIndex()))
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
        return CATEGORY + "/" + ItemIdentity.base(inventoryKey);
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
