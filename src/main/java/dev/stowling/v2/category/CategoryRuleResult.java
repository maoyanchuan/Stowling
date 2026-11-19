package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ClassificationLayoutEntry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CategoryRuleResult(
        boolean succeeded,
        String message,
        Map<String, List<ContainerTarget>> categories,
        List<ClassificationLayoutEntry> layout
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
        layout = List.copyOf(layout);
    }

    public CategoryRuleResult(boolean succeeded, String message,
            Map<String, List<ContainerTarget>> categories) {
        this(succeeded, message, categories, categories.entrySet().stream()
                .map(entry -> new ClassificationLayoutEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    public static CategoryRuleResult success(Map<String, List<ContainerTarget>> categories,
            List<ClassificationLayoutEntry> layout) {
        return new CategoryRuleResult(true, "已识别 " + categories.size() + " 个分类",
                categories, layout);
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
