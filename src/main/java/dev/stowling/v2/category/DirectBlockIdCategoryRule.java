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
        return CategoryRuleResult.failure(markers.isEmpty()
                ? "分类物识别区未找到样品，请检查展示框或其支撑方块是否位于识别区内"
                : "已识别 " + markers.size() + " 个分类标记，但均未匹配储存容器，请检查列坐标");
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
