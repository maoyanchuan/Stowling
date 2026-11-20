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
                "在储存容器上挂展示框，按样品的物品类型收纳不可堆叠变体。整片无标记时收纳所有不可堆叠物品，潜影盒除外。");
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
