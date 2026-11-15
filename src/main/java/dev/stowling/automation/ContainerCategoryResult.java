package dev.stowling.automation;

import java.util.List;
import java.util.Objects;

public record ContainerCategoryResult(
        boolean succeeded,
        List<ContainerCategory> categories,
        String message
) {
    public ContainerCategoryResult {
        categories = List.copyOf(
                Objects.requireNonNull(categories, "categories")
        );
        message = Objects.requireNonNull(message, "message");
    }

    public static ContainerCategoryResult success(
            List<ContainerCategory> categories
    ) {
        return new ContainerCategoryResult(
                true,
                categories,
                "已识别 " + categories.size() + " 个容器分类"
        );
    }

    public static ContainerCategoryResult failure(String message) {
        return new ContainerCategoryResult(false, List.of(), message);
    }
}
