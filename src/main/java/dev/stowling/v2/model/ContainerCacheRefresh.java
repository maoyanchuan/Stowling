package dev.stowling.v2.model;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ContainerCacheRefresh(List<ResourceReference> targets, boolean chests, boolean barrels,
        boolean shulkerBoxes, boolean otherContainers, boolean scanMarkers,
        boolean includeMarkersInInventory, boolean markerOnly, boolean categoryStorageOnlyEnabled,
        boolean multiAreaEnabled, boolean foodPauseResumeEnabled) {
    public static final String MODULE = "REFRESH_CONTAINER_CACHE";
    public static final String TARGET_REF = "targetRef";
    public static final String TARGET_REFS = "targetRefs";
    public static final String MULTI_AREA = "multiAreaEnabled";
    public static final String FOOD_PAUSE = "foodPauseResumeEnabled";
    public static final String CHESTS = "chests";
    public static final String BARRELS = "barrels";
    public static final String SHULKER_BOXES = "shulkerBoxes";
    public static final String OTHER_CONTAINERS = "otherContainers";
    public static final String SCAN_MARKERS = "scanMarkers";
    public static final String INCLUDE_MARKERS = "includeMarkersInInventory";
    public static final String MARKER_ONLY = "markerOnly";
    public static final String CATEGORY_STORAGE_ONLY = "categoryStorageOnlyEnabled";
    public static final Set<String> BOOLEAN_FIELDS = Set.of(CHESTS, BARRELS, SHULKER_BOXES,
            OTHER_CONTAINERS, SCAN_MARKERS, INCLUDE_MARKERS, MARKER_ONLY, CATEGORY_STORAGE_ONLY,
            MULTI_AREA, FOOD_PAUSE);

    public ContainerCacheRefresh {
        targets = List.copyOf(targets).stream().distinct().toList();
        if (targets.isEmpty() || targets.stream().anyMatch(target ->
                target.type() != ResourceType.REGION && target.type() != ResourceType.CATEGORY_ZONE)) {
            throw new IllegalArgumentException("请选择基础区域或仓储布局");
        }
        if ((scanMarkers || includeMarkersInInventory || markerOnly)
                && (targets.size() != 1 || targets.getFirst().type() != ResourceType.CATEGORY_ZONE)) {
            throw new IllegalArgumentException("只有仓储布局支持识别区");
        }
        if (includeMarkersInInventory && (!scanMarkers || markerOnly)) {
            throw new IllegalArgumentException("开启扫描识别区后才可将识别区物品计入库存");
        }
        if (!chests && !barrels && !shulkerBoxes && !otherContainers) {
            throw new IllegalArgumentException("至少启用一类容器");
        }
    }

    public static ContainerCacheRefresh from(Map<String, String> parameters) {
        return new ContainerCacheRefresh(readTargets(parameters),
                readBoolean(parameters, CHESTS, true), readBoolean(parameters, BARRELS, true),
                readBoolean(parameters, SHULKER_BOXES, true),
                readBoolean(parameters, OTHER_CONTAINERS, false),
                readBoolean(parameters, SCAN_MARKERS, false),
                readBoolean(parameters, INCLUDE_MARKERS, false),
                readBoolean(parameters, MARKER_ONLY, false),
                readBoolean(parameters, CATEGORY_STORAGE_ONLY, false),
                readBoolean(parameters, MULTI_AREA, false),
                readBoolean(parameters, FOOD_PAUSE, false));
    }

    public ResourceReference target() {
        return targets.getFirst();
    }

    public boolean foodPauseResume() {
        return foodPauseResumeEnabled && !markerOnly;
    }

    public static List<ResourceReference> readTargets(Map<String, String> parameters) {
        String encoded = parameters.get(TARGET_REFS);
        if (encoded == null) {
            return List.of(ResourceReference.parse(parameters.get(TARGET_REF)));
        }
        try {
            var result = new ArrayList<ResourceReference>();
            for (var value : JsonParser.parseString(encoded).getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("选区列表须为资源引用字符串数组");
                }
                result.add(ResourceReference.parse(value.getAsString()));
            }
            return result.stream().distinct().toList();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("选区列表须为有效的资源引用字符串数组", failure);
        }
    }

    public boolean categoryStorageOnly() {
        return categoryStorageOnly(target());
    }

    public boolean categoryStorageOnly(ResourceReference selected) {
        return categoryStorageOnlyEnabled && !markerOnly
                && selected.type() == ResourceType.CATEGORY_ZONE;
    }

    public static boolean isManualTask(TaskDefinition task) {
        return task.steps().size() == 1
                && MODULE.equals(task.steps().getFirst().moduleId())
                && !readBoolean(task.steps().getFirst().parameters(), MARKER_ONLY, false);
    }

    private static boolean readBoolean(Map<String, String> parameters, String key,
            boolean defaultValue) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be a boolean");
    }
}
