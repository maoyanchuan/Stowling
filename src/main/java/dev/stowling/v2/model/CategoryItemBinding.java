package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Editable binding draft; only itemIdList is used by classification. */
public record CategoryItemBinding(
        String name,
        String targetType,
        String markerId,
        CategoryPositionDraft position,
        String itemIds,
        boolean expanded
) {
    public CategoryItemBinding {
        name = name == null ? "" : name;
        targetType = targetType == null ? "marker-id" : targetType;
        markerId = markerId == null ? "" : markerId;
        itemIds = itemIds == null ? "" : itemIds;
        if (!Set.of("marker-id", "marker-position", "container").contains(targetType)) {
            throw new IllegalArgumentException("物品绑定目标类型无效");
        }
    }

    public CategoryItemBinding(String name, String targetType, String markerId,
            BlockPosition position, String itemIds, boolean expanded) {
        this(name, targetType, markerId, CategoryPositionDraft.of(position), itemIds, expanded);
    }

    public List<String> itemIdList() {
        return Arrays.stream(itemIds.trim().split("\\s+"))
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    public void validate() {
        if (itemIdList().isEmpty() || itemIdList().stream().anyMatch(id -> !validId(id))) {
            throw new IllegalArgumentException("物品绑定请每行填写一个完整物品 ID");
        }
        if ("marker-id".equals(targetType) && !validId(markerId.trim())) {
            throw new IllegalArgumentException("请填写完整的识别物 ID");
        }
        if (!"marker-id".equals(targetType) && position == null) {
            throw new IllegalArgumentException("请填写绑定目标的 X、Y、Z 整数坐标");
        }
        if (!"marker-id".equals(targetType)) position.blockPosition();
    }

    private static boolean validId(String value) {
        return value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
}
