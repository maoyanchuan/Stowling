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
        BlockPosition standingPosition,
        Integer bulkStacks,
        boolean ignoreMarkerPositions,
        List<CategoryPositionDraft> ignoredMarkerPositions,
        boolean ignoreRepeatedBlocks,
        boolean multiItemBindingsEnabled,
        List<CategoryItemBinding> multiItemBindings
) {
    public CategoryZoneOptions {
        scopeMode = choice(scopeMode, "single", Set.of("single", "selection"));
        recognitionMode = choice(recognitionMode, "marker",
                Set.of("marker", "container-item", "unstackable"));
        storageMethod = choice(storageMethod, "bulk", Set.of("bulk", "mixed", "box"));
        noCacheLocation = choice(noCacheLocation, "inside-box", Set.of("inside-box", "specified"));
        cacheDestination = cacheDestination == null ? "" : cacheDestination.trim();
        supportBlock = supportBlock == null ? "" : supportBlock.trim();
        ignoredMarkerPositions = ignoredMarkerPositions == null
                ? List.of() : List.copyOf(ignoredMarkerPositions);
        multiItemBindings = multiItemBindings == null
                ? List.of() : List.copyOf(multiItemBindings);
        if (bulkStacks != null && (bulkStacks < 0 || bulkStacks > 54)) {
            throw new IllegalArgumentException("散货占比请填写 0 到 54 之间的整数组数");
        }
        if (Boolean.TRUE.equals(specifyStanding) && standingPosition == null) {
            throw new IllegalArgumentException("请填写站立位置的 X、Y、Z 整数坐标");
        }
        if (Boolean.TRUE.equals(specifyStanding) && standingPosition.y() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("站立脚下方块 Y 坐标过大");
        }
    }

    public CategoryZoneOptions(
            String scopeMode, String recognitionMode, String storageMethod,
            String noCacheLocation, String cacheDestination, boolean ignoreSupportBlock,
            String supportBlock, Boolean specifyStanding, BlockPosition standingPosition,
            Integer bulkStacks
    ) {
        this(scopeMode, recognitionMode, storageMethod, noCacheLocation, cacheDestination,
                ignoreSupportBlock, supportBlock, specifyStanding, standingPosition, bulkStacks,
                false, List.of(), false, false, List.of());
    }

    public void validateActiveRules() {
        if (ignoreMarkerPositions && ignoredMarkerPositions.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一个排除标记坐标");
        }
        excludedMarkerPositions();
        if (!multiItemBindingsEnabled) return;
        if (multiItemBindings.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一条物品绑定规则");
        }
        for (CategoryItemBinding rule : multiItemBindings) {
            rule.validate();
            if (ignoreMarkerPositions && "marker-position".equals(rule.targetType())
                    && ignoredMarkerPositions.contains(rule.position())) {
                throw new IllegalArgumentException("物品绑定的目标标记已被坐标排除");
            }
        }
    }

    public List<BlockPosition> excludedMarkerPositions() {
        return ignoreMarkerPositions ? ignoredMarkerPositions.stream()
                .map(CategoryPositionDraft::blockPosition).toList() : List.of();
    }

    public CategoryZoneOptions(
            String scopeMode, String recognitionMode, String storageMethod,
            String noCacheLocation, String cacheDestination, boolean ignoreSupportBlock,
            String supportBlock, Boolean specifyStanding, BlockPosition standingPosition
    ) {
        this(scopeMode, recognitionMode, storageMethod, noCacheLocation, cacheDestination,
                ignoreSupportBlock, supportBlock, specifyStanding, standingPosition, null);
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
        // The editable position is the support block; navigation uses the player's feet.
        int feetY = standingPosition.y() + 1;
        return new AreaBounds(standingPosition.x(), feetY, standingPosition.z(),
                standingPosition.x(), feetY, standingPosition.z());
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
        // An explicit mixed reserve must not be borrowed to satisfy the legacy full-box rule.
        boolean allowPartial = mode == StorageMode.MIXED && bulkStacks != null
                || base.allowPartialBoxes();
        return new StoragePolicy(mode, base.loosePercent(), allowPartial,
                base.packingArea(), base.refillLoose(),
                bulkStacks == null ? base.looseSlots() : bulkStacks,
                "specified".equals(noCacheLocation) ? cacheDestination : null);
    }

    private static String choice(String value, String fallback, Set<String> allowed) {
        String result = value == null ? fallback : value.trim();
        if (!allowed.contains(result)) throw new IllegalArgumentException("分类区设置选项无效");
        return result;
    }
}
