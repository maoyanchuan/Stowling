package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import java.util.List;
import java.util.Map;

/** The runtime expands this layout category using the cache's actual stack limits. */
public final class UnstackableCategoryRule implements CategoryRule {
    public static final String ID = "UNSTACKABLE_ITEMS";
    public static final String CATEGORY = "stowling:unstackable_items";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(ID, "不可堆叠物品（排除潜影盒）",
                "储存实际堆叠上限为 1 的物品，排除所有潜影盒；无需分类物识别区。");
    }

    @Override
    public CategoryRuleResult resolve(List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers) {
        return storageContainers.isEmpty() ? CategoryRuleResult.failure("储存区没有可用容器")
                : CategoryRuleResult.success(Map.of(CATEGORY, storageContainers));
    }

    @Override
    public boolean accepts(CategoryMarker marker) {
        return false;
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }
}
