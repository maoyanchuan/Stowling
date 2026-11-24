package dev.stowling.v2.model;

import dev.stowling.domain.BlockPosition;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public record CategoryZoneOptions(
        String scopeMode,
        String recognitionMode,
        String storageMethod,
        String noCacheLocation,
        String cacheDestination,
        boolean ignoreSupportBlock,
        String supportBlock,
        Boolean specifyStanding,
        BlockPosition standingPosition
) {
    public CategoryZoneOptions {
        scopeMode = choice(scopeMode, "single", Set.of("single", "selection"));
        recognitionMode = choice(recognitionMode, "marker",
                Set.of("marker", "container-item", "unstackable"));
        storageMethod = choice(storageMethod, "bulk", Set.of("bulk", "mixed", "box"));
        noCacheLocation = choice(noCacheLocation, "inside-box", Set.of("inside-box", "specified"));
        cacheDestination = cacheDestination == null ? "" : cacheDestination.trim();
        supportBlock = supportBlock == null ? "" : supportBlock.trim();
        if (Boolean.TRUE.equals(specifyStanding) && standingPosition == null) {
            throw new IllegalArgumentException("请填写站立位置的 X、Y、Z 整数坐标");
        }
    }

    public CategoryZoneOptions(
            String scopeMode, String recognitionMode, String storageMethod,
            String noCacheLocation, String cacheDestination, boolean ignoreSupportBlock,
            String supportBlock
    ) {
        this(scopeMode, recognitionMode, storageMethod, noCacheLocation, cacheDestination,
                ignoreSupportBlock, supportBlock, null, null);
    }

    public AreaBounds standingArea(AreaBounds previous) {
        if (specifyStanding == null) return previous;
        if (!specifyStanding) return null;
        return new AreaBounds(standingPosition.x(), standingPosition.y(), standingPosition.z(),
                standingPosition.x(), standingPosition.y(), standingPosition.z());
    }

    public String ruleId() {
        return switch (recognitionMode) {
            case "container-item" -> "MARKER_CONTAINER_CONTENTS";
            case "unstackable" -> "UNSTACKABLE_ITEMS";
            default -> "DIRECT_BLOCK_ID";
        };
    }

    public List<String> ignoredBlockIds() {
        if (!ignoreSupportBlock) return List.of();
        var values = Arrays.stream(supportBlock.split("[\\s,，;；]+"))
                .filter(value -> !value.isBlank()).toList();
        if (values.isEmpty()) throw new IllegalArgumentException("请填写要忽略的支撑方块 ID");
        return values;
    }

    public StoragePolicy storagePolicy(StoragePolicy previous) {
        StoragePolicy base = previous == null ? StoragePolicy.DEFAULT : previous;
        StorageMode mode = switch (storageMethod) {
            case "mixed" -> StorageMode.MIXED;
            case "box" -> StorageMode.BOX_ONLY;
            default -> StorageMode.NORMAL;
        };
        if ("specified".equals(noCacheLocation) && cacheDestination.isBlank()) {
            throw new IllegalArgumentException("请选择指定缓存位置");
        }
        return new StoragePolicy(mode, base.loosePercent(), base.allowPartialBoxes(),
                base.packingArea(), base.refillLoose(), base.looseSlots(),
                "specified".equals(noCacheLocation) ? cacheDestination : null);
    }

    private static String choice(String value, String fallback, Set<String> allowed) {
        String result = value == null ? fallback : value.trim();
        if (!allowed.contains(result)) throw new IllegalArgumentException("分类区设置选项无效");
        return result;
    }
}
