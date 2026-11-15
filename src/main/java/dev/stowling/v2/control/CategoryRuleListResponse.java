package dev.stowling.v2.control;

import dev.stowling.v2.category.CategoryRuleDefinition;
import java.util.List;

public record CategoryRuleListResponse(
        List<CategoryRuleDefinition> rules
) {
    public CategoryRuleListResponse {
        rules = List.copyOf(rules);
    }
}
