package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DirectBlockIdCategoryRule implements CategoryRule {
    public static final String ID = "DIRECT_BLOCK_ID";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(
                ID,
                "方块 ID 精确匹配",
                "分类区方块注册 ID 必须与待存物品注册 ID 完全相同。"
        );
    }

    @Override
    public CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers
    ) {
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("储存区没有可用容器");
        }
        if (markers.isEmpty()) {
            return CategoryRuleResult.failure("分类物识别区没有方块");
        }
        PairingAxis axis = pairingAxis(markers);
        Map<String, List<ContainerTarget>> categories =
                new LinkedHashMap<>();
        for (CategoryMarker marker : markers) {
            List<ContainerTarget> matching = storageContainers.stream()
                    .filter(container -> matchesColumn(
                            axis,
                            marker.position(),
                            container.position()
                    ))
                    .toList();
            if (matching.isEmpty()) {
                continue;
            }
            List<ContainerTarget> categoryContainers =
                    categories.computeIfAbsent(
                            marker.blockId(),
                            ignored -> new ArrayList<>()
                    );
            for (ContainerTarget container : matching) {
                if (!categoryContainers.contains(container)) {
                    categoryContainers.add(container);
                }
            }
        }
        if (categories.isEmpty()) {
            return CategoryRuleResult.failure(
                    "分类物识别区没有与储存区列对应的方块"
            );
        }
        Map<String, List<ContainerTarget>> immutable = new LinkedHashMap<>();
        categories.forEach((categoryId, containers) ->
                immutable.put(categoryId, List.copyOf(containers))
        );
        return CategoryRuleResult.success(immutable);
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }

    private static PairingAxis pairingAxis(List<CategoryMarker> markers) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (CategoryMarker marker : markers) {
            BlockPosition position = marker.position();
            minX = Math.min(minX, position.x());
            minY = Math.min(minY, position.y());
            minZ = Math.min(minZ, position.z());
            maxX = Math.max(maxX, position.x());
            maxY = Math.max(maxY, position.y());
            maxZ = Math.max(maxZ, position.z());
        }
        int spanX = maxX - minX;
        int spanY = maxY - minY;
        int spanZ = maxZ - minZ;
        if (spanX == 0 && spanY == 0 && spanZ == 0) {
            return PairingAxis.ALL;
        }
        if (spanX >= spanY && spanX >= spanZ) {
            return PairingAxis.X;
        }
        return spanY >= spanZ ? PairingAxis.Y : PairingAxis.Z;
    }

    private static boolean matchesColumn(
            PairingAxis axis,
            BlockPosition marker,
            BlockPosition storage
    ) {
        return switch (axis) {
            case X -> marker.x() == storage.x();
            case Y -> marker.y() == storage.y();
            case Z -> marker.z() == storage.z();
            case ALL -> true;
        };
    }

    private enum PairingAxis {
        X,
        Y,
        Z,
        ALL
    }
}
