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
                "标记箱/木桶",
                "读取标记箱或木桶的全部物品，默认对应同列储存区；可选择对应整片储存区。"
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
        return resolve(markers, storageContainers, overrides, false);
    }

    public CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            List<CategoryContainerOverrideDefinition> overrides,
            boolean wholeStorage
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
                false,
                wholeStorage
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
