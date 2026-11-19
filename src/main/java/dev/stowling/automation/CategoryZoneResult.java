package dev.stowling.automation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CategoryZoneResult(
        boolean succeeded,
        String message,
        Map<String, List<ContainerTarget>> categories,
        List<ClassificationLayoutEntry> layout
) {
    public CategoryZoneResult {
        message = Objects.requireNonNull(message, "message");
        Map<String, List<ContainerTarget>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<ContainerTarget>> entry
                : Objects.requireNonNull(categories, "categories").entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        categories = Collections.unmodifiableMap(copied);
        layout = List.copyOf(layout);
    }

    public CategoryZoneResult(boolean succeeded, String message,
            Map<String, List<ContainerTarget>> categories) {
        this(succeeded, message, categories, categories.entrySet().stream()
                .map(entry -> new ClassificationLayoutEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    public static CategoryZoneResult success(Map<String, List<ContainerTarget>> categories,
            List<ClassificationLayoutEntry> layout) {
        return new CategoryZoneResult(true, "已解析 " + categories.size() + " 个分类",
                categories, layout);
    }

    public static CategoryZoneResult success(
            Map<String, List<ContainerTarget>> categories
    ) {
        return new CategoryZoneResult(
                true,
                "已解析 " + categories.size() + " 个分类",
                categories
        );
    }

    public static CategoryZoneResult failure(String message) {
        return new CategoryZoneResult(false, message, Map.of());
    }
}
