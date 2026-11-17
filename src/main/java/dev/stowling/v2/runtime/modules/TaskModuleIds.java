package dev.stowling.v2.runtime.modules;

public final class TaskModuleIds {
    public static final String CONTAINER_INTERACTION = "CONTAINER_INTERACTION";
    public static final String MOVE_TO_COORDINATE = "MOVE_TO_COORDINATE";
    public static final String COLLECT_DROPPED_ITEMS = "COLLECT_DROPPED_ITEMS";
    public static final String SCAN_CONTAINERS_IN_REGION =
            "SCAN_CONTAINERS_IN_REGION";
    public static final String RESOLVE_CONTAINER_CATEGORIES =
            "RESOLVE_CONTAINER_CATEGORIES";
    public static final String SELECT_CONTAINER = "SELECT_CONTAINER";
    public static final String WITHDRAW_ITEMS = "WITHDRAW_ITEMS";
    public static final String SELECT_STORAGE_TARGET = "SELECT_STORAGE_TARGET";
    public static final String DEPOSIT_ITEMS_TO_CONTAINERS =
            "DEPOSIT_ITEMS_TO_CONTAINERS";
    public static final String WITHDRAW_FOOD = "WITHDRAW_FOOD";
    public static final String EAT_FOOD = "EAT_FOOD";
    public static final String SCAN_CATEGORY_MARKERS = "SCAN_CATEGORY_MARKERS";
    public static final String SELECT_MARKER_CONTAINER = "SELECT_MARKER_CONTAINER";
    public static final String READ_CONTAINER = "READ_CONTAINER";
    public static final String SAVE_CATEGORY_MARKER_CACHE =
            "SAVE_CATEGORY_MARKER_CACHE";
    public static final String ASSERT_NO_MANAGED_ITEMS =
            "ASSERT_NO_MANAGED_ITEMS";
    public static final String WAIT_FOR_DROPPED_ITEMS =
            "WAIT_FOR_DROPPED_ITEMS";

    public static final String TARGET_REF = "targetRef";
    public static final String AREA_REF = "areaRef";
    public static final String REGION_REF = "regionRef";
    public static final String CATEGORY_ZONE_REF = "categoryZoneRef";
    public static final String CATEGORY_BINDING_ID = "categoryBindingId";
    public static final String CATEGORY_RULE_ID = "categoryRuleId";
    public static final String TASK_REF = "taskRef";
    public static final String CONDITION = "condition";
    public static final String NEGATE = "negate";
    public static final String MAX_ITERATIONS = "maxIterations";
    public static final String PRIORITY = "priority";
    public static final String ITEM_SOURCE = "itemSource";
    public static final String ITEM_FILTER = "itemFilter";
    public static final String FOOD_ITEM_ID = "foodItemId";
    public static final String FOOD_LEVEL_THRESHOLD = "foodLevelThreshold";
    public static final String CONTAINER_TYPES = "containerTypes";
    public static final String MAX_VOLUME = "maxVolume";
    public static final String TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String OPERATION = "operation";
    public static final String MARKER_SOURCES = "markerSources";
    public static final String PICKUP_RADIUS = "pickupRadius";
    public static final String RETRY_ON_FAILURE = "retryOnFailure";
    public static final String ALLOW_CACHE_FALLBACK =
            "allowCacheFallback";
    public static final String ALLOW_EMPTY_CONTAINER_SCAN =
            "allowEmptyContainerScan";

    public static final String COORDINATE_ID = "coordinateId";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String Z = "z";
    public static final String REGION_ID = "regionId";
    public static final String CATEGORY_ZONE_ID = "categoryZoneId";
    public static final String CACHE_REGION_ID = "cacheRegionId";
    public static final String AREA_REFERENCE = "area";

    private TaskModuleIds() {
    }
}
