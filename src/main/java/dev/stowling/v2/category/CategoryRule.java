package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import java.util.List;

public interface CategoryRule {
    CategoryRuleDefinition definition();

    default String id() {
        return definition().id();
    }

    CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers
    );

    default CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            List<CategoryContainerOverrideDefinition> overrides
    ) {
        return resolve(markers, storageContainers);
    }

    default boolean accepts(CategoryMarker marker) {
        return true;
    }

    boolean matchesItem(String categoryId, String itemId);
}
