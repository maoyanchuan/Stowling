package dev.stowling.v2.category;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CategoryRuleRegistry {
    private final Map<String, CategoryRule> rules;
    private final List<CategoryRuleDefinition> definitions;

    public CategoryRuleRegistry(List<CategoryRule> rules) {
        Map<String, CategoryRule> byId = new LinkedHashMap<>();
        for (CategoryRule rule : rules) {
            CategoryRule previous = byId.putIfAbsent(rule.id(), rule);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate category rule: " + rule.id()
                );
            }
        }
        this.rules = Map.copyOf(byId);
        this.definitions = rules.stream()
                .map(CategoryRule::definition)
                .toList();
    }

    public static CategoryRuleRegistry standard() {
        return new CategoryRuleRegistry(List.of(
                new DirectBlockIdCategoryRule()
        ));
    }

    public CategoryRule require(String id) {
        CategoryRule rule = rules.get(
                Objects.requireNonNull(id, "id")
        );
        if (rule == null) {
            throw new IllegalArgumentException(
                    "unknown category rule: " + id
            );
        }
        return rule;
    }

    public List<CategoryRuleDefinition> definitions() {
        return definitions;
    }
}
