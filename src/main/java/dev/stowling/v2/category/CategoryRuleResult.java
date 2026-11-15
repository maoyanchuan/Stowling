package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CategoryRuleResult(
        boolean succeeded,
        String message,
        Map<String, List<ContainerTarget>> categories
) {
    public CategoryRuleResult {
        message = Objects.requireNonNull(message, "message");
        Map<String, List<ContainerTarget>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<ContainerTarget>> entry
                : Objects.requireNonNull(categories, "categories").entrySet()) {
            copied.put(
                    Objects.requireNonNull(entry.getKey(), "category id"),
                    List.copyOf(entry.getValue())
            );
        }
        categories = Collections.unmodifiableMap(copied);
    }

    public static CategoryRuleResult success(
            Map<String, List<ContainerTarget>> categories
    ) {
        return new CategoryRuleResult(
                true,
                "已识别 " + categories.size() + " 个分类",
                categories
        );
    }

    public static CategoryRuleResult failure(String message) {
        return new CategoryRuleResult(false, message, Map.of());
    }
}
