package dev.stowling.v2.model;

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
        String supportBlock
) {
    public CategoryZoneOptions {
        scopeMode = choice(scopeMode, "single", Set.of("single", "selection"));
        recognitionMode = choice(recognitionMode, "marker",
                Set.of("marker", "container-item", "unstackable"));
        storageMethod = choice(storageMethod, "bulk", Set.of("bulk", "mixed", "box"));
        noCacheLocation = choice(noCacheLocation, "inside-box", Set.of("inside-box", "specified"));
        cacheDestination = cacheDestination == null ? "" : cacheDestination.trim();
        supportBlock = supportBlock == null ? "" : supportBlock.trim();
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
