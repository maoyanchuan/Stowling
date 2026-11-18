package dev.stowling.v2.model;

import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TaskFlowTemplates {
    public static final String CONDITION = "condition";
    public static final String NEGATE = "negate";
    public static final String MAX_ITERATIONS = "maxIterations";
    public static final String DEFAULT_MAX_ITERATIONS = "1024";
    public static final String RESET_CACHE_HAS_MORE = "resetCacheHasMore";

    private TaskFlowTemplates() {
    }

    public static List<TaskStep> autoEat(
            String coordinateRef,
            String foodItemId,
            String foodLevelThreshold
    ) {
        String threshold = foodLevelThreshold == null
                ? "18"
                : foodLevelThreshold;
        Map<String, String> condition = conditionParameters(
                TaskCondition.FOOD_BELOW_THRESHOLD,
                Map.of(ConfigValidator.FOOD_LEVEL_THRESHOLD, threshold)
        );
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(ConfigValidator.TARGET_REF, coordinateRef)
        );
        TaskStep withdraw = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.WITHDRAW_FOOD,
                Map.of(
                        ConfigValidator.TARGET_REF, coordinateRef,
                        ConfigValidator.FOOD_ITEM_ID, foodItemId
                )
        );
        TaskStep eat = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.EAT_FOOD,
                Map.of(ConfigValidator.FOOD_ITEM_ID, foodItemId)
        );
        TaskStep eatUntilFull = TaskStep.repeat(
                UUID.randomUUID(),
                condition,
                List.of(withdraw, eat)
        );
        return List.of(TaskStep.conditional(
                UUID.randomUUID(),
                condition,
                List.of(move, eatUntilFull),
                List.of()
        ));
    }

    public static List<TaskStep> sortByCategoryZone(
            String cacheRegionRef,
            String categoryZoneRef,
            Map<String, String> scanParameters
    ) {
        return sortByCategoryZone(
                cacheRegionRef,
                categoryZoneRef,
                scanParameters,
                List.of()
        );
    }

    public static List<TaskStep> sortByCategoryZone(
            String cacheRegionRef,
            String categoryZoneRef,
            Map<String, String> scanParameters,
            List<TaskStep> preResolveSteps
    ) {
        Map<String, String> scan = new LinkedHashMap<>(scanParameters);
        scan.remove(ConfigValidator.MARKER_SOURCES);
        scan.remove(ConfigValidator.ALLOW_EMPTY_CONTAINER_SCAN);
        scan.remove(ConfigValidator.CATEGORY_BINDING_ID);
        scan.remove(ConfigValidator.CATEGORY_RULE_ID);
        scan.put(ConfigValidator.REGION_REF, cacheRegionRef);
        scan.put(ConfigValidator.SCAN_CONTENTS, "true");
        TaskStep scanStep = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                scan
        );
        Map<String, String> resolve = new LinkedHashMap<>(scanParameters);
        resolve.remove(ConfigValidator.REGION_REF);
        resolve.put(ConfigValidator.CATEGORY_ZONE_REF, categoryZoneRef);
        TaskStep resolveStep = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                resolve
        );
        List<TaskStep> classify = new ArrayList<>(preResolveSteps);
        classify.add(resolveStep);
        classify.addAll(sortTail());
        return List.of(
                scanStep,
                TaskStep.conditional(
                        UUID.randomUUID(),
                        conditionParameters(
                                TaskCondition.HAS_CONTAINERS,
                                Map.of()
                        ),
                        classify,
                        List.of()
                )
        );
    }

    public static List<TaskStep> collect(
            String areaRef,
            String regionRef,
            Map<String, String> collectParameters
    ) {
        return collect(areaRef, regionRef, collectParameters, false);
    }

    public static List<TaskStep> collect(String areaRef, String regionRef,
            Map<String, String> collectParameters, boolean overflowEnabled) {
        Map<String, String> collect = new LinkedHashMap<>(collectParameters);
        collect.remove(ConfigValidator.CONTAINER_TYPES);
        collect.remove(ConfigValidator.MAX_VOLUME);
        collect.put(ConfigValidator.AREA_REF, areaRef);
        TaskStep collectStep = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.COLLECT_DROPPED_ITEMS,
                collect
        );
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(ConfigValidator.REGION_REF, regionRef);
        copyIfPresent(
                collectParameters,
                scan,
                ConfigValidator.CONTAINER_TYPES
        );
        copyIfPresent(collectParameters, scan, ConfigValidator.MAX_VOLUME);
        TaskStep scanStep = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                scan
        );
        TaskStep selectContainer = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_CONTAINER,
                Map.of(RESET_CACHE_HAS_MORE, "true")
        );
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep deposit = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode(),
                        ConfigValidator.ITEM_SOURCE, "collected",
                        ConfigValidator.RETRY_ON_FAILURE, "true"
                )
        );
        TaskStep depositCollected = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_CONTAINERS, Map.of()),
                List.of(selectContainer, move, deposit)
        );
        List<TaskStep> result = new ArrayList<>(List.of(collectStep, scanStep, depositCollected));
        if (overflowEnabled) {
            result.add(overflow(null));
        }
        result.addAll(reconcileInventory(regionRef, collectParameters, overflowEnabled));
        return List.copyOf(result);
    }

    public static TaskStep overflow(String cacheRegionRef) {
        return TaskStep.action(UUID.randomUUID(), ConfigValidator.PACK_OVERFLOW,
                cacheRegionRef == null ? Map.of() : Map.of(ConfigValidator.REGION_REF,
                        cacheRegionRef));
    }

    public static List<TaskStep> reconcileInventory(
            String regionRef,
            Map<String, String> collectParameters
    ) {
        return reconcileInventory(regionRef, collectParameters, false);
    }

    public static List<TaskStep> reconcileInventory(String regionRef,
            Map<String, String> collectParameters, boolean overflowEnabled) {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(ConfigValidator.REGION_REF, regionRef);
        copyIfPresent(collectParameters, scan, ConfigValidator.CONTAINER_TYPES);
        copyIfPresent(collectParameters, scan, ConfigValidator.MAX_VOLUME);
        Map<String, String> syncParameters = new LinkedHashMap<>();
        copyIfPresent(
                collectParameters,
                syncParameters,
                ConfigValidator.ITEM_FILTER
        );
        TaskStep syncInventory = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SYNC_PLAYER_INVENTORY,
                syncParameters
        );
        TaskStep reconcileScan = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                scan
        );
        TaskStep reconcileSelect = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_CONTAINER,
                Map.of(RESET_CACHE_HAS_MORE, "true")
        );
        TaskStep reconcileMove = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep reconcileDeposit = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode(),
                        ConfigValidator.ITEM_SOURCE, "collected",
                        ConfigValidator.RETRY_ON_FAILURE, "true"
                )
        );
        TaskStep reconcileDeposits = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_CONTAINERS, Map.of()),
                List.of(
                        reconcileSelect,
                        reconcileMove,
                        reconcileDeposit
                )
        );
        TaskStep reconcileSync = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SYNC_PLAYER_INVENTORY,
                syncParameters
        );
        List<TaskStep> reconciliation = new ArrayList<>(List.of(reconcileScan, reconcileDeposits));
        if (overflowEnabled) {
            reconciliation.add(overflow(null));
        }
        reconciliation.add(reconcileSync);
        TaskStep reconcileLatePickups = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_MANAGED_ITEMS, Map.of()),
                reconciliation
        );
        TaskStep assertClean = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of()
        );
        TaskStep assertInventory = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_PLAYER_INVENTORY_RESTORED,
                Map.of()
        );
        return List.of(
                syncInventory,
                reconcileLatePickups,
                assertClean,
                assertInventory
        );
    }

    public static List<TaskStep> depositCollected(
            String regionRef,
            boolean includeScan
    ) {
        List<TaskStep> result = new ArrayList<>();
        if (includeScan) {
            result.add(TaskStep.action(
                    UUID.randomUUID(),
                    ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                    Map.of(ConfigValidator.REGION_REF, regionRef)
            ));
        }
        TaskStep select = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_CONTAINER,
                Map.of(RESET_CACHE_HAS_MORE, "true")
        );
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep deposit = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode(),
                        ConfigValidator.ITEM_SOURCE, "collected"
                )
        );
        result.add(TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_CONTAINERS, Map.of()),
                List.of(select, move, deposit)
        ));
        result.add(TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of()
        ));
        return List.copyOf(result);
    }

    public static List<TaskStep> refreshMarkers(
            String categoryZoneRef,
            String maxVolume
    ) {
        return refreshMarkers(categoryZoneRef, maxVolume, null);
    }

    public static List<TaskStep> refreshMarkers(
            String categoryZoneRef,
            String maxVolume,
            String markerSources
    ) {
        return refreshMarkers(
                categoryZoneRef,
                maxVolume,
                markerSources,
                null,
                null
        );
    }

    public static List<TaskStep> refreshMarkers(
            String categoryZoneRef,
            String maxVolume,
            String markerSources,
            String bindingId,
            String ruleId
    ) {
        Map<String, String> scan = new LinkedHashMap<>();
        scan.put(ConfigValidator.CATEGORY_ZONE_REF, categoryZoneRef);
        if (maxVolume != null && !maxVolume.isBlank()) {
            scan.put(ConfigValidator.MAX_VOLUME, maxVolume);
        }
        if (markerSources != null && !markerSources.isBlank()) {
            scan.put(ConfigValidator.MARKER_SOURCES, markerSources);
        }
        if (bindingId != null && !bindingId.isBlank()) {
            scan.put(ConfigValidator.CATEGORY_BINDING_ID, bindingId);
        }
        if (ruleId != null && !ruleId.isBlank()) {
            scan.put(ConfigValidator.CATEGORY_RULE_ID, ruleId);
        }
        TaskStep scanStep = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CATEGORY_MARKERS,
                scan
        );
        TaskStep selectMarker = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_MARKER_CONTAINER,
                Map.of()
        );
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("marker").encode()
                )
        );
        TaskStep read = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.READ_CONTAINER,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("marker").encode()
                )
        );
        TaskStep processMarkers = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(
                        TaskCondition.HAS_MARKER_CONTAINERS,
                        Map.of()
                ),
                List.of(selectMarker, move, read)
        );
        TaskStep save = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SAVE_CATEGORY_MARKER_CACHE,
                Map.of(ConfigValidator.CATEGORY_ZONE_REF, categoryZoneRef)
        );
        return List.of(scanStep, processMarkers, save);
    }

    public static List<TaskStep> interruption(String regionRef) {
        TaskStep scan = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                Map.of(ConfigValidator.REGION_REF, regionRef)
        );
        TaskStep select = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_CONTAINER,
                Map.of(RESET_CACHE_HAS_MORE, "true")
        );
        TaskStep move = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep deposit = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode(),
                        ConfigValidator.ITEM_SOURCE, "managed",
                        ConfigValidator.RETRY_ON_FAILURE, "true"
                )
        );
        TaskStep depositManaged = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_MANAGED_ITEMS, Map.of()),
                List.of(select, move, deposit)
        );
        TaskStep assertClean = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of()
        );
        return List.of(scan, depositManaged, assertClean);
    }

    private static List<TaskStep> sortTail() {
        TaskStep selectContainer = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_CONTAINER,
                Map.of(RESET_CACHE_HAS_MORE, "true")
        );
        TaskStep moveToContainer = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode()
                )
        );
        TaskStep withdraw = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.WITHDRAW_ITEMS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("container").encode(),
                        ConfigValidator.ITEM_SOURCE, "classified"
                )
        );
        TaskStep selectStorage = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.SELECT_STORAGE_TARGET,
                Map.of(ConfigValidator.ALLOW_CACHE_FALLBACK, "true")
        );
        TaskStep moveToStorage = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("storage").encode()
                )
        );
        TaskStep deposit = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        ResourceReference.output("storage").encode(),
                        ConfigValidator.ITEM_SOURCE, "carried",
                        ConfigValidator.RETRY_ON_FAILURE, "true"
                )
        );
        TaskStep depositCarried = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_CARRIED_ITEMS, Map.of()),
                List.of(selectStorage, moveToStorage, deposit)
        );
        TaskStep withdrawFromContainer = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.CACHE_HAS_MORE, Map.of()),
                List.of(moveToContainer, withdraw, depositCarried)
        );
        TaskStep processContainers = TaskStep.repeat(
                UUID.randomUUID(),
                conditionParameters(TaskCondition.HAS_CONTAINERS, Map.of()),
                List.of(selectContainer, withdrawFromContainer)
        );
        TaskStep assertClean = TaskStep.action(
                UUID.randomUUID(),
                ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                Map.of()
        );
        return List.of(processContainers, assertClean);
    }

    private static Map<String, String> conditionParameters(
            TaskCondition condition,
            Map<String, String> extra
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(CONDITION, condition.name());
        result.putAll(extra);
        return Map.copyOf(result);
    }

    private static void copyIfPresent(
            Map<String, String> source,
            Map<String, String> target,
            String key
    ) {
        String value = source.get(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
