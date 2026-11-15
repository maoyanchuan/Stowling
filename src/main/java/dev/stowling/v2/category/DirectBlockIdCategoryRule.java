package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class DirectBlockIdCategoryRule implements CategoryRule {
    public static final String ID = "DIRECT_BLOCK_ID";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(
                ID,
                "方块 ID 精确匹配",
                "分类区方块注册 ID 必须与待存物品注册 ID 完全相同。"
        );
    }

    @Override
    public CategoryRuleResult resolve(
            List<String> markerBlockIds,
            List<ContainerTarget> storageContainers
    ) {
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("储存区没有可用容器");
        }
        Map<String, List<ContainerTarget>> categories =
                new LinkedHashMap<>();
        for (String blockId : new LinkedHashSet<>(markerBlockIds)) {
            categories.put(blockId, storageContainers);
        }
        if (categories.isEmpty()) {
            return CategoryRuleResult.failure("分类物识别区没有方块");
        }
        return CategoryRuleResult.success(categories);
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }
}
