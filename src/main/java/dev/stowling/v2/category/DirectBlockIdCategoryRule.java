package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class DirectBlockIdCategoryRule implements CategoryRule {
    public static final String ID = "DIRECT_BLOCK_ID";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(
                ID,
                "自动展示物精确匹配",
                "自动读取展示框、展示架和摆放方块，并将物品 ID 与待存物品精确比较。"
        );
    }

    @Override
    public CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers
    ) {
        return resolve(markers, storageContainers, List.of());
    }

    @Override
    public CategoryRuleResult resolve(
            List<CategoryMarker> markers,
            List<ContainerTarget> storageContainers,
            List<CategoryContainerOverrideDefinition> overrides
    ) {
        if (storageContainers.isEmpty()) {
            return CategoryRuleResult.failure("储存区没有可用容器");
        }
        Set<BlockPosition> replacedPositions = overrides.stream()
                .filter(override ->
                        override.mode() == CategoryContainerOverrideMode.REPLACE)
                .map(CategoryContainerOverrideDefinition::storagePosition)
                .collect(Collectors.toUnmodifiableSet());
        PairingAxis axis = pairingAxis(storageContainers);
        Map<String, List<ContainerTarget>> categories =
                new LinkedHashMap<>();
        Map<ColumnKey, Set<String>> columnItems = new LinkedHashMap<>();
        Map<ColumnKey, BlockPosition> columnPositions = new LinkedHashMap<>();
        for (CategoryMarker marker : markers) {
            List<ContainerTarget> matching = storageContainers.stream()
                    .filter(container -> matchesColumn(
                            axis,
                            marker.position(),
                            container.position()
                    ) && !replacedPositions.contains(container.position()))
                    .toList();
            if (matching.isEmpty()) {
                continue;
            }
            ColumnKey column = ColumnKey.of(axis, marker.position());
            columnItems.computeIfAbsent(
                    column,
                    ignored -> new LinkedHashSet<>()
            ).add(marker.itemId());
            columnPositions.putIfAbsent(column, marker.position());
            List<ContainerTarget> categoryContainers =
                    categories.computeIfAbsent(
                            marker.itemId(),
                            ignored -> new ArrayList<>()
                    );
            for (ContainerTarget container : matching) {
                if (!categoryContainers.contains(container)) {
                    categoryContainers.add(container);
                }
            }
        }
        for (Map.Entry<ColumnKey, Set<String>> entry : columnItems.entrySet()) {
            if (entry.getValue().size() > 1) {
                BlockPosition position = columnPositions.get(entry.getKey());
                return CategoryRuleResult.failure(
                        "分类物识别区同一列出现多个物品 " + position
                                + ": "
                                + String.join(", ", entry.getValue())
                );
            }
        }
        for (CategoryContainerOverrideDefinition override : overrides) {
            List<ContainerTarget> matching = storageContainers.stream()
                    .filter(container -> container.position().equals(
                            override.storagePosition()
                    ))
                    .toList();
            if (matching.isEmpty()) {
                return CategoryRuleResult.failure(
                        "容器例外规则没有匹配到储存区容器 "
                                + override.storagePosition()
                );
            }
            for (ContainerTarget container : matching) {
                if (override.mode() == CategoryContainerOverrideMode.REPLACE) {
                    removeContainer(categories, container);
                }
                for (String itemId : override.itemIds()) {
                    addCategory(categories, itemId, container);
                }
            }
        }
        if (categories.isEmpty()) {
            return CategoryRuleResult.failure(
                    "没有可分配给储存区容器的分类物品"
            );
        }
        Map<String, List<ContainerTarget>> immutable = new LinkedHashMap<>();
        categories.forEach((categoryId, containers) ->
                immutable.put(categoryId, List.copyOf(containers))
        );
        return CategoryRuleResult.success(immutable);
    }

    @Override
    public boolean accepts(CategoryMarker marker) {
        return marker.source() != CategoryMarkerSource.CONTAINER_CONTENTS;
    }

    private static void addCategory(
            Map<String, List<ContainerTarget>> categories,
            String itemId,
            ContainerTarget container
    ) {
        List<ContainerTarget> containers = categories.computeIfAbsent(
                itemId,
                ignored -> new ArrayList<>()
        );
        if (!containers.contains(container)) {
            containers.add(container);
        }
    }

    private static void removeContainer(
            Map<String, List<ContainerTarget>> categories,
            ContainerTarget container
    ) {
        for (List<ContainerTarget> containers : categories.values()) {
            containers.removeIf(container::equals);
        }
        categories.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
    }

    private static PairingAxis pairingAxis(
            List<ContainerTarget> storageContainers
    ) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ContainerTarget target : storageContainers) {
            BlockPosition position = target.position();
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

    private record ColumnKey(PairingAxis axis, int coordinate) {
        private static ColumnKey of(
                PairingAxis axis,
                BlockPosition position
        ) {
            int coordinate = switch (axis) {
                case X -> position.x();
                case Y -> position.y();
                case Z -> position.z();
                case ALL -> 0;
            };
            return new ColumnKey(axis, coordinate);
        }
    }
}
