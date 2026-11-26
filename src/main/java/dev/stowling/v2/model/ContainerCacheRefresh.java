package dev.stowling.v2.model;

import java.util.Map;
import java.util.Set;

public record ContainerCacheRefresh(ResourceReference target, boolean chests, boolean barrels,
        boolean shulkerBoxes, boolean otherContainers, boolean scanMarkers,
        boolean includeMarkersInInventory, boolean markerOnly, boolean categoryStorageOnlyEnabled) {
    public static final String MODULE = "REFRESH_CONTAINER_CACHE";
    public static final String TARGET_REF = "targetRef";
    public static final String CHESTS = "chests";
    public static final String BARRELS = "barrels";
    public static final String SHULKER_BOXES = "shulkerBoxes";
    public static final String OTHER_CONTAINERS = "otherContainers";
    public static final String SCAN_MARKERS = "scanMarkers";
    public static final String INCLUDE_MARKERS = "includeMarkersInInventory";
    public static final String MARKER_ONLY = "markerOnly";
    public static final String CATEGORY_STORAGE_ONLY = "categoryStorageOnlyEnabled";
    public static final Set<String> BOOLEAN_FIELDS = Set.of(CHESTS, BARRELS, SHULKER_BOXES,
            OTHER_CONTAINERS, SCAN_MARKERS, INCLUDE_MARKERS, MARKER_ONLY, CATEGORY_STORAGE_ONLY);

    public ContainerCacheRefresh {
        if (target.type() != ResourceType.REGION && target.type() != ResourceType.CATEGORY_ZONE) {
            throw new IllegalArgumentException("请选择基础区域或仓储布局");
        }
        if ((scanMarkers || includeMarkersInInventory || markerOnly)
                && target.type() != ResourceType.CATEGORY_ZONE) {
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
        return new ContainerCacheRefresh(ResourceReference.parse(parameters.get(TARGET_REF)),
                readBoolean(parameters, CHESTS, true), readBoolean(parameters, BARRELS, true),
                readBoolean(parameters, SHULKER_BOXES, true),
                readBoolean(parameters, OTHER_CONTAINERS, false),
                readBoolean(parameters, SCAN_MARKERS, false),
                readBoolean(parameters, INCLUDE_MARKERS, false),
                readBoolean(parameters, MARKER_ONLY, false),
                readBoolean(parameters, CATEGORY_STORAGE_ONLY, false));
    }

    public boolean categoryStorageOnly() {
        return categoryStorageOnlyEnabled && !markerOnly
                && target.type() == ResourceType.CATEGORY_ZONE;
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
