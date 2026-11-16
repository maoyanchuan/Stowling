package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import java.util.List;

public final class ContainerContentsCategoryRule implements CategoryRule {
    public static final String ID = "MARKER_CONTAINER_CONTENTS";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(
                ID,
                "分类标记容器内容",
                "读取识别区内箱子或木桶的全部物品槽位，并把每种物品分配给同一列储存区。"
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
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("储存区没有可用容器");
        }
        if (markers.isEmpty()) {
            return CategoryRuleResult.failure("分类标记容器没有物品");
        }
        CategoryRuleResult result = ColumnCategoryMatcher.resolve(
                markers,
                storageContainers,
                overrides,
                false
        );
        if (!result.succeeded() || !result.categories().isEmpty()) {
            return result;
        }
        return CategoryRuleResult.failure(
                "分类标记容器内容没有对应到储存区"
        );
    }

    @Override
    public boolean accepts(CategoryMarker marker) {
        return marker.source() == CategoryMarkerSource.CONTAINER_CONTENTS;
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }
}
