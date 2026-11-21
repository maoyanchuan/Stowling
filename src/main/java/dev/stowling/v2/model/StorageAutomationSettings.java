package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.UUID;

public record StorageAutomationSettings(
        boolean cleanupEnabled,
        List<BlockPosition> temporaryContainers,
        List<BlockPosition> emptyBoxContainers,
        BlockPosition fallbackPackingPosition,
        List<java.util.UUID> temporaryCoordinateIds,
        List<java.util.UUID> emptyBoxCoordinateIds,
        java.util.UUID fallbackCoordinateId,
        List<java.util.UUID> temporaryRegionIds,
        List<java.util.UUID> overflowCoordinateIds,
        List<java.util.UUID> overflowRegionIds,
        java.util.UUID unstackableOverflowRegionId,
        Boolean robotWarehouseEnabled,
        List<UUID> robotWarehouseCoordinateIds,
        List<UUID> robotWarehouseRegionIds
) {
    public StorageAutomationSettings(boolean cleanupEnabled, List<BlockPosition> temporary,
            List<BlockPosition> empty, BlockPosition fallback, List<UUID> temporaryCoordinateIds,
            List<UUID> emptyBoxCoordinateIds, UUID fallbackCoordinateId,
            List<UUID> temporaryRegionIds, List<UUID> overflowCoordinateIds,
            List<UUID> overflowRegionIds, UUID unstackableOverflowRegionId,
            Boolean robotWarehouseEnabled) {
        this(cleanupEnabled, temporary, empty, fallback, temporaryCoordinateIds,
                emptyBoxCoordinateIds, fallbackCoordinateId, temporaryRegionIds,
                overflowCoordinateIds, overflowRegionIds, unstackableOverflowRegionId,
                robotWarehouseEnabled, List.of(), List.of());
    }

    public StorageAutomationSettings(boolean cleanupEnabled, List<BlockPosition> temporary,
            List<BlockPosition> empty, BlockPosition fallback, List<UUID> temporaryCoordinateIds,
            List<UUID> emptyBoxCoordinateIds, UUID fallbackCoordinateId,
            List<UUID> temporaryRegionIds, List<UUID> overflowCoordinateIds,
            List<UUID> overflowRegionIds, UUID unstackableOverflowRegionId) {
        this(cleanupEnabled, temporary, empty, fallback, temporaryCoordinateIds,
                emptyBoxCoordinateIds, fallbackCoordinateId, temporaryRegionIds,
                overflowCoordinateIds, overflowRegionIds, unstackableOverflowRegionId, null);
    }

    public boolean warehouseEnabled() {
        return !robotWarehouseCoordinateIds.isEmpty() || !robotWarehouseRegionIds.isEmpty();
    }

    public boolean hasWarehouseLocation(ResourceReference ref) {
        return switch (ref.type()) {
            case COORDINATE -> robotWarehouseCoordinateIds.contains(ref.uuid());
            case REGION -> robotWarehouseRegionIds.contains(ref.uuid());
            default -> false;
        };
    }
    public StorageAutomationSettings(
            boolean cleanupEnabled,
            List<BlockPosition> temporaryContainers,
            List<BlockPosition> emptyBoxContainers,
            BlockPosition fallbackPackingPosition,
            List<java.util.UUID> temporaryCoordinateIds,
            List<java.util.UUID> emptyBoxCoordinateIds,
            java.util.UUID fallbackCoordinateId,
            List<java.util.UUID> temporaryRegionIds,
            List<java.util.UUID> overflowCoordinateIds,
            List<java.util.UUID> overflowRegionIds
    ) {
        this(cleanupEnabled, temporaryContainers, emptyBoxContainers, fallbackPackingPosition,
                temporaryCoordinateIds, emptyBoxCoordinateIds, fallbackCoordinateId,
                temporaryRegionIds, overflowCoordinateIds, overflowRegionIds, null);
    }

    public StorageAutomationSettings(
            boolean cleanupEnabled,
            List<BlockPosition> temporaryContainers,
            List<BlockPosition> emptyBoxContainers,
            BlockPosition fallbackPackingPosition,
            List<java.util.UUID> temporaryCoordinateIds,
            List<java.util.UUID> emptyBoxCoordinateIds,
            java.util.UUID fallbackCoordinateId,
            List<java.util.UUID> temporaryRegionIds
    ) {
        this(cleanupEnabled, temporaryContainers, emptyBoxContainers, fallbackPackingPosition,
                temporaryCoordinateIds, emptyBoxCoordinateIds, fallbackCoordinateId,
                temporaryRegionIds, List.of(), List.of());
    }

    public static final StorageAutomationSettings DEFAULT = new StorageAutomationSettings(
            false, List.of(), List.of(), null);

    public StorageAutomationSettings {
        robotWarehouseCoordinateIds = robotWarehouseCoordinateIds == null ? List.of()
                : List.copyOf(robotWarehouseCoordinateIds);
        robotWarehouseRegionIds = robotWarehouseRegionIds == null ? List.of()
                : List.copyOf(robotWarehouseRegionIds);
        overflowCoordinateIds = overflowCoordinateIds == null ? List.of()
                : List.copyOf(overflowCoordinateIds);
        overflowRegionIds = overflowRegionIds == null ? List.of() : List.copyOf(overflowRegionIds);
        if (unstackableOverflowRegionId != null
                && overflowRegionIds.contains(unstackableOverflowRegionId)) {
            throw new IllegalArgumentException("不可堆叠溢出区域必须与普通溢出区域分开配置");
        }
        if (overflowCoordinateIds.size() + overflowRegionIds.size() > 64) {
            throw new IllegalArgumentException("溢出设施最多引用 64 个点位或区域");
        }
        if (emptyBoxCoordinateIds != null
                && overflowCoordinateIds.stream().anyMatch(emptyBoxCoordinateIds::contains)) {
            throw new IllegalArgumentException("溢出箱与空盒供应箱必须分开配置");
        }
        temporaryRegionIds = temporaryRegionIds == null ? List.of()
                : List.copyOf(temporaryRegionIds);
        temporaryCoordinateIds = temporaryCoordinateIds == null ? List.of()
                : List.copyOf(temporaryCoordinateIds);
        emptyBoxCoordinateIds = emptyBoxCoordinateIds == null ? List.of()
                : List.copyOf(emptyBoxCoordinateIds);
        if (robotWarehouseCoordinateIds.size() + robotWarehouseRegionIds.size() > 64) {
            throw new IllegalArgumentException("机器人仓库最多引用 64 个点位或区域");
        }
        if (robotWarehouseCoordinateIds.stream().anyMatch(overflowCoordinateIds::contains)
                || robotWarehouseRegionIds.stream().anyMatch(overflowRegionIds::contains)
                || unstackableOverflowRegionId != null
                        && robotWarehouseRegionIds.contains(unstackableOverflowRegionId)) {
            throw new IllegalArgumentException("机器人仓库与溢出设施必须分开配置");
        }
        if (robotWarehouseCoordinateIds.stream().anyMatch(emptyBoxCoordinateIds::contains)) {
            throw new IllegalArgumentException("机器人仓库与空盒供应箱必须分开配置");
        }
        temporaryContainers = temporaryContainers == null ? List.of()
                : List.copyOf(temporaryContainers);
        emptyBoxContainers = emptyBoxContainers == null ? List.of()
                : List.copyOf(emptyBoxContainers);
        if (temporaryContainers.size() > 64 || emptyBoxContainers.size() > 64) {
            throw new IllegalArgumentException("全局容器列表最多配置 64 个位置");
        }
        if (temporaryCoordinateIds.size() + temporaryRegionIds.size() > 64) {
            throw new IllegalArgumentException("暂存设施最多引用 64 个点位或区域");
        }
        if (cleanupEnabled && temporaryContainers.isEmpty() && temporaryCoordinateIds.isEmpty()
                && temporaryRegionIds.isEmpty()) {
            throw new IllegalArgumentException("开启全局收尾前必须配置暂存箱");
        }
        if (temporaryContainers.stream().anyMatch(emptyBoxContainers::contains)) {
            throw new IllegalArgumentException("暂存箱与空盒供应箱必须分开配置");
        }
        if (temporaryCoordinateIds.stream().anyMatch(emptyBoxCoordinateIds::contains)) {
            throw new IllegalArgumentException("暂存与供应不能引用同一坐标");
        }
    }

    public StorageAutomationSettings(boolean cleanupEnabled, List<BlockPosition> temporary,
            List<BlockPosition> empty, BlockPosition fallback) {
        this(cleanupEnabled, temporary, empty, fallback, List.of(), List.of(), null);
    }

    public StorageAutomationSettings(boolean cleanupEnabled, List<BlockPosition> temporary,
            List<BlockPosition> empty, BlockPosition fallback,
            List<java.util.UUID> temporaryCoordinateIds, List<java.util.UUID> emptyBoxCoordinateIds,
            java.util.UUID fallbackCoordinateId) {
        this(cleanupEnabled, temporary, empty, fallback, temporaryCoordinateIds,
                emptyBoxCoordinateIds, fallbackCoordinateId, List.of());
    }
}
