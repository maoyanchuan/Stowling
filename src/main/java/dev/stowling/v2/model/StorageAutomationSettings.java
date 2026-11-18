package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;
import java.util.List;

public record StorageAutomationSettings(
        boolean cleanupEnabled,
        List<BlockPosition> temporaryContainers,
        List<BlockPosition> emptyBoxContainers,
        BlockPosition fallbackPackingPosition
) {
    public static final StorageAutomationSettings DEFAULT = new StorageAutomationSettings(
            false, List.of(), List.of(), null);

    public StorageAutomationSettings {
        temporaryContainers = temporaryContainers == null ? List.of()
                : List.copyOf(temporaryContainers);
        emptyBoxContainers = emptyBoxContainers == null ? List.of()
                : List.copyOf(emptyBoxContainers);
        if (temporaryContainers.size() > 64 || emptyBoxContainers.size() > 64) {
            throw new IllegalArgumentException("全局容器列表最多配置 64 个位置");
        }
        if (cleanupEnabled && temporaryContainers.isEmpty()) {
            throw new IllegalArgumentException("开启全局收尾前必须配置暂存箱");
        }
        if (temporaryContainers.stream().anyMatch(emptyBoxContainers::contains)) {
            throw new IllegalArgumentException("暂存箱与空盒供应箱必须分开配置");
        }
    }
}
