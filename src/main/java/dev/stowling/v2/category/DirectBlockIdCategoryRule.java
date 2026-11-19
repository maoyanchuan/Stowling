package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import java.util.List;

public final class DirectBlockIdCategoryRule implements CategoryRule {
    public static final String ID = "DIRECT_BLOCK_ID";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(
                ID,
                "智能识别",
                "自动读取摆放方块、展示框、展示架和花盆内物品，"
                        + "并将物品 ID 与待存物品精确比较。"
        );
    }

    @Override
    public CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers
    ) {
        return resolve(markers, storageContainers, List.of());
    }

    @Override
    public CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            List<CategoryContainerOverrideDefinition> overrides
    ) {
        CategoryRuleResult result = ColumnCategoryMatcher.resolve(
                markers,
                storageContainers,
                overrides,
                true
        );
        if (!result.succeeded() || !result.categories().isEmpty()) {
            return result;
        }
        return CategoryRuleResult.failure(
                "没有可分配给储存区容器的分类物品"
        );
    }

    public static CategoryRuleResult resolveExplicitSources(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            List<CategoryContainerOverrideDefinition> overrides
    ) {
        return resolveExplicitSources(markers, storageContainers, overrides, false);
    }

    public static CategoryRuleResult resolveExplicitSources(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            List<CategoryContainerOverrideDefinition> overrides,
            boolean wholeStorage
    ) {
        return ColumnCategoryMatcher.resolve(
                markers,
                storageContainers,
                overrides,
                false,
                wholeStorage
        );
    }

    @Override
    public boolean accepts(CategoryMarker marker) {
        return marker.source() != CategoryMarkerSource.CONTAINER_CONTENTS;
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }
}
