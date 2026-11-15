package dev.stowling.v2.category;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ContainerContentsCategoryRule implements CategoryRule {
    public static final String ID = "MARKER_CONTAINER_CONTENTS";

    @Override
    public CategoryRuleDefinition definition() {
        return new CategoryRuleDefinition(
                ID,
                "分类标记容器内容",
                "读取识别区内箱子或木桶的全部物品槽位，并把每种物品分配给同一列储存区。"
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
        if (markers.isEmpty()) {
            return CategoryRuleResult.failure("分类标记容器没有物品");
        }
        PairingAxis axis = pairingAxis(storageContainers);
        Set<BlockPosition> replacedPositions = overrides.stream()
                .filter(override ->
                        override.mode() == CategoryContainerOverrideMode.REPLACE)
                .map(CategoryContainerOverrideDefinition::storagePosition)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, List<ContainerTarget>> categories =
                new LinkedHashMap<>();
        for (CategoryMarker marker : markers) {
            List<ContainerTarget> matching = storageContainers.stream()
                    .filter(container -> matchesColumn(
                            axis,
                            marker.position(),
                            container.position()
                    ) && !replacedPositions.contains(container.position()))
                    .toList();
            for (ContainerTarget container : matching) {
                addCategory(categories, marker.itemId(), container);
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
                    "分类标记容器内容没有对应到储存区"
            );
        }
        Map<String, List<ContainerTarget>> immutable = new LinkedHashMap<>();
        categories.forEach((itemId, containers) ->
                immutable.put(itemId, List.copyOf(containers))
        );
        return CategoryRuleResult.success(immutable);
    }

    @Override
    public boolean accepts(CategoryMarker marker) {
        return marker.source() == CategoryMarkerSource.CONTAINER_CONTENTS;
    }

    @Override
    public boolean matchesItem(String categoryId, String itemId) {
        return categoryId.equals(itemId);
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
}
