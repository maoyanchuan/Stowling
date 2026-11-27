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
        Integer bulkStacks,
        boolean ignoreMarkerPositions,
        List<CategoryPositionDraft> ignoredMarkerPositions,
        boolean ignoreRepeatedBlocks,
        boolean multiItemBindingsEnabled,
        List<CategoryItemBinding> multiItemBindings,
        String unstackableOverflowRef
) {
    public CategoryZoneOptions(String scopeMode, String recognitionMode, String storageMethod,
            String noCacheLocation, String cacheDestination, boolean ignoreSupportBlock,
            String supportBlock, Integer bulkStacks, boolean ignoreMarkerPositions,
            List<CategoryPositionDraft> ignoredMarkerPositions, boolean ignoreRepeatedBlocks,
            boolean multiItemBindingsEnabled, List<CategoryItemBinding> multiItemBindings) {
        this(scopeMode, recognitionMode, storageMethod, noCacheLocation, cacheDestination,
                ignoreSupportBlock, supportBlock, bulkStacks, ignoreMarkerPositions,
                ignoredMarkerPositions, ignoreRepeatedBlocks, multiItemBindingsEnabled,
                multiItemBindings, null);
    }

    public CategoryZoneOptions(String scopeMode, String recognitionMode, String storageMethod,
            String noCacheLocation, String cacheDestination, boolean ignoreSupportBlock,
            String supportBlock, Integer bulkStacks) {
        this(scopeMode, recognitionMode, storageMethod, noCacheLocation, cacheDestination,
                ignoreSupportBlock, supportBlock, bulkStacks, false, List.of(), false, false,
                List.of());
    }

    public CategoryZoneOptions(String scopeMode, String recognitionMode, String storageMethod,
            String noCacheLocation, String cacheDestination, boolean ignoreSupportBlock,
            String supportBlock) {
        this(scopeMode, recognitionMode, storageMethod, noCacheLocation, cacheDestination,
                ignoreSupportBlock, supportBlock, null);
    }

    public CategoryZoneOptions {
        unstackableOverflowRef = unstackableOverflowRef == null
                ? "" : unstackableOverflowRef.trim();
        if (!unstackableOverflowRef.isEmpty()) {
            var ref = ResourceReference.parse(unstackableOverflowRef);
            if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION) {
                throw new IllegalArgumentException("不可堆叠溢出区请选择坐标点或容器区域");
            }
            unstackableOverflowRef = ref.encode();
        }
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
