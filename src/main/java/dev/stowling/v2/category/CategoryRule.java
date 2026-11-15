package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import java.util.List;

public interface CategoryRule {
    CategoryRuleDefinition definition();

    default String id() {
        return definition().id();
    }

    CategoryRuleResult resolve(
            List<String> markerBlockIds,
            List<ContainerTarget> storageContainers
    );

    boolean matchesItem(String categoryId, String itemId);
}
