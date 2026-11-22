package dev.stowling.v2.config;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition.Kind;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneTiler;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskCondition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskFolderDefinition;
import dev.stowling.v2.model.TaskFlowTemplates;
import dev.stowling.v2.model.TaskInteractionCompiler;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ConfigValidator {
    public static final String CONTAINER_INTERACTION = "CONTAINER_INTERACTION";
    public static final String MOVE_TO_COORDINATE = "MOVE_TO_COORDINATE";
    public static final String COLLECT_DROPPED_ITEMS = "COLLECT_DROPPED_ITEMS";
    public static final String CREATE_AFTER_SUCCESS = "afterSuccess";
    public static final String SCAN_CONTAINERS_IN_REGION =
            "SCAN_CONTAINERS_IN_REGION";
    public static final String RESOLVE_CONTAINER_CATEGORIES =
            "RESOLVE_CONTAINER_CATEGORIES";
    public static final String RESOLVE_UNSTACKABLE_STORAGE = "RESOLVE_UNSTACKABLE_STORAGE";
    public static final String SORT_UNSTACKABLE_ITEMS = "SORT_UNSTACKABLE_ITEMS";
    public static final String SELECT_CONTAINER = "SELECT_CONTAINER";
    public static final String WITHDRAW_ITEMS = "WITHDRAW_ITEMS";
    public static final String PACK_OVERFLOW = "PACK_OVERFLOW";
    public static final String ROBOT_WAREHOUSE = "ROBOT_WAREHOUSE";
    public static final String SELECT_STORAGE_TARGET = "SELECT_STORAGE_TARGET";
    public static final String DEPOSIT_ITEMS_TO_CONTAINERS =
            "DEPOSIT_ITEMS_TO_CONTAINERS";
    public static final String WITHDRAW_FOOD = "WITHDRAW_FOOD";
    public static final String EAT_FOOD = "EAT_FOOD";
    public static final String SCAN_CATEGORY_MARKERS = "SCAN_CATEGORY_MARKERS";
    public static final String SELECT_MARKER_CONTAINER =
            "SELECT_MARKER_CONTAINER";
    public static final String READ_CONTAINER = "READ_CONTAINER";
    public static final String SAVE_CATEGORY_MARKER_CACHE =
            "SAVE_CATEGORY_MARKER_CACHE";
    public static final String ASSERT_NO_MANAGED_ITEMS =
            "ASSERT_NO_MANAGED_ITEMS";
    public static final String ASSERT_PLAYER_INVENTORY_RESTORED =
            "ASSERT_PLAYER_INVENTORY_RESTORED";
    public static final String SYNC_COLLECTED_INVENTORY =
            "SYNC_COLLECTED_INVENTORY";
    public static final String SYNC_PLAYER_INVENTORY = "SYNC_PLAYER_INVENTORY";

    public static final String WAIT_FOR_DROPPED_ITEMS =
            "WAIT_FOR_DROPPED_ITEMS";
    public static final String COLLECT_DROPPED_ITEMS_IN_REGION =
            "COLLECT_DROPPED_ITEMS_IN_REGION";
    public static final String AUTO_EAT_AT_COORDINATE =
            "AUTO_EAT_AT_COORDINATE";
    public static final String SORT_CACHE_ITEMS = "SORT_CACHE_ITEMS";
    public static final String SORT_CACHE_ITEMS_BY_CATEGORY_ZONE =
            "SORT_CACHE_ITEMS_BY_CATEGORY_ZONE";
    public static final String REFRESH_CATEGORY_MARKER_CACHE =
            "REFRESH_CATEGORY_MARKER_CACHE";

    public static final String TARGET_REF = "targetRef";
    public static final String AREA_REF = "areaRef";
    public static final String REGION_REF = "regionRef";
    public static final String CATEGORY_ZONE_REF = "categoryZoneRef";
    public static final String CATEGORY_BINDING_ID = "categoryBindingId";
    public static final String CATEGORY_RULE_ID = "categoryRuleId";
    public static final String TASK_REF = "taskRef";
    public static final String COORDINATE_ID = "coordinateId";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String Z = "z";
    public static final String REGION_ID = "regionId";
    public static final String CACHE_REGION_ID = "cacheRegionId";
    public static final String CATEGORY_ZONE_ID = "categoryZoneId";
    public static final String AREA_REFERENCE = "area";
    public static final String FOOD_ITEM_ID = "foodItemId";
    public static final String RESERVE_FOOD = "reserveFood";
    public static final String INTERACT_WITH_TARGET = "interactWithTarget";
    public static final String FOOD_LEVEL_THRESHOLD = "foodLevelThreshold";
    public static final String ITEM_FILTER = "itemFilter";
    public static final String TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String CONTAINER_TYPES = "containerTypes";
    public static final String MAX_VOLUME = "maxVolume";
    public static final String SCAN_SOURCE = "scanSource";
    public static final String ITEM_SOURCE = "itemSource";
    public static final String FILL_MODE = "fillMode";
    public static final String ALLOW_EMPTY_CONTAINER_SCAN =
            "allowEmptyContainerScan";
    public static final String MARKER_SOURCES = "markerSources";
    public static final String PICKUP_RADIUS = "pickupRadius";
    public static final String DEFAULT_PICKUP_RADIUS = "defaultPickupRadius";
    public static final String CACHE_LOCATION_REF = "cacheLocationRef";
    public static final String RETRY_ON_FAILURE = "retryOnFailure";
    public static final String SCAN_CONTENTS = "scanContents";
    public static final String ALLOW_CACHE_FALLBACK =
            "allowCacheFallback";
    public static final String PRIORITY = "priority";

    private static final Set<String> MANUAL_COORDINATE_KEYS =
            Set.of(X, Y, Z);


    private ConfigValidator() {
    }

    public static void requireValid(AppConfig config) {
        requireUniqueNames(
                config.coordinates().stream().map(CoordinatePoint::name).toList(),
                "coordinate"
        );
        requireUniqueNames(
                config.tasks().stream().map(TaskDefinition::name).toList(),
                "task"
        );
        requireUniqueNames(
                config.regions().stream().map(RegionDefinition::name).toList(),
                "region"
        );
        requireUniqueNames(
                config.categoryZones().stream()
                        .map(CategoryZoneDefinition::name)
                        .toList(),
                "category zone"
        );
        requireUniqueNames(
                config.triggers().stream()
                        .map(TaskTriggerDefinition::name)
                        .toList(),
                "task trigger"
        );
        requireUniqueNames(
                config.taskFolders().stream()
                        .map(TaskFolderDefinition::name)
                        .toList(),
                "task folder"
        );
        requireUniqueNames(
                config.autoClassifyTasks().stream()
                        .map(AutoClassifyDefinition::name)
                        .toList(),
                "auto classify task"
        );
        Map<UUID, CoordinatePoint> coordinates = new LinkedHashMap<>();
        for (CoordinatePoint point : config.coordinates()) {
            if (coordinates.putIfAbsent(point.id(), point) != null) {
                throw new V2ValidationException("duplicate coordinate id");
            }
        }
        Map<UUID, RegionDefinition> regions = new LinkedHashMap<>();
        for (RegionDefinition region : config.regions()) {
            if (regions.putIfAbsent(region.id(), region) != null) {
                throw new V2ValidationException("duplicate region id");
            }
        }
        Map<UUID, CategoryZoneDefinition> categoryZones =
                validateCategoryZones(config.categoryZones());
        config.categoryZones().forEach(zone -> zone.bindings().forEach(binding ->
                requirePartialDestination(binding.storagePolicy(), coordinates, regions)));
        Map<UUID, TaskFolderDefinition> taskFolders = new LinkedHashMap<>();
        for (TaskFolderDefinition folder : config.taskFolders()) {
            if (taskFolders.putIfAbsent(folder.id(), folder) != null) {
                throw new V2ValidationException("duplicate task folder id");
            }
        }
        Map<UUID, TaskDefinition> tasks = new LinkedHashMap<>();
        for (TaskDefinition task : config.tasks()) {
            requireCleanupDestination(task.cleanupPolicy(), coordinates, regions);
            if (tasks.putIfAbsent(task.id(), task) != null) {
                throw new V2ValidationException("duplicate task id");
            }
            if (task.folderId() != null
                    && !taskFolders.containsKey(task.folderId())) {
                throw new V2ValidationException(
                        "task folder does not exist: " + task.folderId()
                );
            }
        }
        int defaultInterruptionFlows = 0;
        for (int taskIndex = 0; taskIndex < config.tasks().size(); taskIndex++) {
            TaskDefinition task = config.tasks().get(taskIndex);
            if (task.defaultInterruptionFlow()) {
                defaultInterruptionFlows++;
            }
            requireInterruption(task, tasks);
            for (int stepIndex = 0; stepIndex < task.steps().size(); stepIndex++) {
                TaskStep step = task.steps().get(stepIndex);
                try {
                    requireValidStep(step, coordinates, regions, categoryZones, tasks.keySet());
                } catch (V2ValidationException exception) {
                    throw new V2ValidationException(
                            "tasks[" + taskIndex + "].steps[" + stepIndex + "]: "
                                    + exception.getMessage(),
                            exception
                    );
                }
            }
            validateCompiledInteraction(
                    task,
                    config.triggers(),
                    coordinates,
                    regions,
                    categoryZones,
                    tasks.keySet()
            );
        }
        if (defaultInterruptionFlows > 1) {
            throw new V2ValidationException(
                    "only one default interruption flow is allowed"
            );
        }
        validateTriggers(config.triggers(), coordinates, regions, tasks);
        validateCoordinateCatalog(config, coordinates, regions, categoryZones);
        validateAutoClassifyTasks(
                config.autoClassifyTasks(),
                config.triggers(),
                coordinates,
                regions,
                categoryZones,
                tasks
        );
    }

    private static void validateAutoClassifyTasks(
            List<AutoClassifyDefinition> definitions,
            List<TaskTriggerDefinition> triggers,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            Map<UUID, TaskDefinition> tasks
    ) {
        Set<UUID> ids = new HashSet<>();
        Set<UUID> triggerIds = triggers.stream()
                .map(TaskTriggerDefinition::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> hidden = new HashSet<>();
        for (var definition : definitions) {
            hidden.add(definition.interruptionTaskId());
            if (definition.kind() == Kind.COMBINED) hidden.add(definition.classificationTaskId());
        }
        for (AutoClassifyDefinition definition : definitions) {
            requireCleanupDestination(definition.cleanupPolicy(), coordinates, regions);
            requireWarehouseDestination(definition.overflowDestinationRef(), coordinates, regions);
            if (!ids.add(definition.id())) {
                throw new V2ValidationException(
                        "duplicate auto classify task id"
                );
            }
            if (definition.detectionCoordinateRef() != null) {
                var coordinate = ResourceReference.parse(definition.detectionCoordinateRef());
                if (!coordinates.containsKey(coordinate.uuid())) {
                    throw new V2ValidationException("掉落物检测点不存在");
                }
            }
            if (definition.followUpTaskRef() != null) {
                UUID target = ResourceReference.parse(definition.followUpTaskRef()).uuid();
                if (hidden.contains(target) || !tasks.containsKey(target)) {
                    throw new V2ValidationException("请选择一个已保存的任务作为结束后的任务");
                }
                requireAcyclicFollowUp(definition.id(), target, tasks);
            }
            requireSpatialReference(definition.cacheLocationRef(), coordinates, regions);
            if (definition.unstackableRegionRef() != null && !regions.containsKey(
                    ResourceReference.parse(definition.unstackableRegionRef()).uuid())) {
                throw new V2ValidationException("不可堆叠储存区不存在");
            }
            Set<UUID> selectedZones = new HashSet<>();
            for (AutoClassifyAreaDefinition area
                    : definition.classificationAreas()) {
                requirePartialDestination(area.storagePolicy(), coordinates, regions);
                ResourceReference zoneReference = ResourceReference.parse(
                        area.categoryZoneRef()
                );
                CategoryZoneDefinition zone = categoryZones.get(
                        zoneReference.uuid()
                );
                if (zone == null) {
                    throw new V2ValidationException(
                            "auto classify category zone does not exist"
                    );
                }
                if (!selectedZones.add(zone.id())) {
                    throw new V2ValidationException(
                            "auto classify zones must be unique"
                    );
                }
            }
            requireAutoTask(
                    tasks,
                    definition.id(),
                    TaskInterruptionMode.CUSTOM
            );
            if (definition.kind() == Kind.COMBINED) {
                requireAutoTask(tasks, definition.classificationTaskId(), TaskInterruptionMode.CUSTOM);
            }
            requireAutoTask(
                    tasks,
                    definition.interruptionTaskId(),
                    TaskInterruptionMode.NONE
            );
            boolean collectionTrigger = triggerIds.contains(
                    definition.collectionTriggerId()
            );
            boolean classificationTrigger = triggerIds.contains(
                    definition.classificationTriggerId()
            );
            if (definition.kind() == Kind.COMBINED && definition.classificationEnabled()
                    && !definition.collectionEnabled()) {
                throw new V2ValidationException(
                        "classification requires collection phase"
                );
            }
            boolean expectCollection = definition.enabled()
                    && definition.collectionEnabled();
            if ((expectCollection && !collectionTrigger)
                    || (!expectCollection && collectionTrigger)
                    || classificationTrigger) {
                throw new V2ValidationException(
                        "auto classify task triggers do not match enabled phases"
                );
            }
        }
    }

    private static void requireAcyclicFollowUp(UUID source, UUID target,
            Map<UUID, TaskDefinition> tasks) {
        var pending = new java.util.ArrayDeque<UUID>();
        var seen = new HashSet<UUID>();
        pending.add(target);
        while (!pending.isEmpty()) {
            UUID next = pending.remove();
            if (next.equals(source)) {
                throw new V2ValidationException("结束后创建任务不能形成循环");
            }
            TaskDefinition task = tasks.get(next);
            if (task != null && seen.add(next)) enqueueCreatedTasks(task.steps(), pending);
        }
    }

    private static void enqueueCreatedTasks(List<TaskStep> steps,
            java.util.ArrayDeque<UUID> pending) {
        for (TaskStep step : steps) {
            if (step.kind() == TaskStepKind.CREATE_TASK) {
                pending.add(ResourceReference.parse(step.parameters().get(TASK_REF)).uuid());
            }
            enqueueCreatedTasks(step.steps(), pending);
            enqueueCreatedTasks(step.otherwise(), pending);
        }
    }

    private static void requireCleanupDestination(
            dev.stowling.v2.model.TaskCleanupPolicy policy,
            Map<UUID, CoordinatePoint> coordinates, Map<UUID, RegionDefinition> regions) {
        requireSpatialReference(policy.destinationRef(), coordinates, regions);
        requireSpatialReference(policy.packingPositionRef(), coordinates, regions);
        policy.emptyBoxRefs().forEach(ref -> requireSpatialReference(ref, coordinates, regions));
    }

    private static void requireSpatialReference(String value, Map<UUID, CoordinatePoint> points,
            Map<UUID, RegionDefinition> regions) {
        if (value == null) return;
        var ref = ResourceReference.parse(value);
        boolean exists = switch (ref.type()) {
            case COORDINATE -> points.containsKey(ref.uuid());
            case REGION -> regions.containsKey(ref.uuid());
            case WAREHOUSE -> true; // Checked against the directory after step validation.
            default -> false;
        };
        if (!exists) throw new V2ValidationException("坐标资源不存在或类型不符：" + value);
    }

    private static void validateCoordinateCatalog(AppConfig config,
            Map<UUID, CoordinatePoint> coordinates, Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> zones) {
        var catalog = config.coordinateCatalog();
        requireUniqueNames(catalog.folders().stream().map(TaskFolderDefinition::name).toList(),
                "坐标文件夹");
        requireUniqueNames(catalog.warehouses().stream().map(value -> value.name()).toList(), "仓库");
        var folders = new HashSet<UUID>();
        for (var folder : catalog.folders()) {
            if (!folders.add(folder.id())) throw new V2ValidationException("重复的坐标文件夹 ID");
        }
        var warehouses = new HashSet<UUID>();
        for (var warehouse : catalog.warehouses()) {
            if (!warehouses.add(warehouse.id())) throw new V2ValidationException("重复的仓库 ID");
            warehouse.members().forEach(ref -> requireSpatialReference(ref, coordinates, regions));
        }
        var validBookmarks = new HashSet<>(config.coordinateBookmarkIds());
        var ordered = new HashSet<String>();
        for (String ref : catalog.order()) {
            if (!validBookmarks.contains(ref) || !ordered.add(ref)) {
                throw new V2ValidationException("坐标排序包含重复或不存在的条目");
            }
        }
        catalog.placements().forEach((value, folder) -> {
            if (!folders.contains(folder)) throw new V2ValidationException("坐标文件夹不存在");
            var ref = ResourceReference.parse(value);
            if (ref.type() == ResourceType.CATEGORY_ZONE) {
                if (!zones.containsKey(ref.uuid())) throw new V2ValidationException("分类区不存在");
            } else {
                requireSpatialReference(value, coordinates, regions);
                requireWarehouseReference(value, warehouses);
            }
        });
        for (var task : config.tasks()) {
            requireWarehouseReference(task.cleanupPolicy().destinationRef(), warehouses);
            requireStepWarehouses(task.steps(), warehouses);
        }
        for (var task : config.autoClassifyTasks()) {
            requireWarehouseReference(task.cacheLocationRef(), warehouses);
            requireWarehouseReference(task.overflowDestinationRef(), warehouses);
            requireWarehouseReference(task.cleanupPolicy().destinationRef(), warehouses);
        }
    }

    private static void requireStepWarehouses(List<TaskStep> steps, Set<UUID> warehouses) {
        for (TaskStep step : steps) {
            requireWarehouseReference(step.parameters().get(CACHE_LOCATION_REF), warehouses);
            requireWarehouseReference(step.parameters().get(TARGET_REF), warehouses);
            requireStepWarehouses(step.steps(), warehouses);
            requireStepWarehouses(step.otherwise(), warehouses);
        }
    }

    private static void requireWarehouseReference(String value, Set<UUID> warehouses) {
        if (value != null && value.startsWith("warehouse:")
                && !warehouses.contains(ResourceReference.parse(value).uuid())) {
            throw new V2ValidationException("任务或文件夹引用的仓库不存在");
        }
    }

    private static void requirePartialDestination(dev.stowling.v2.model.StoragePolicy policy,
            Map<UUID, CoordinatePoint> coordinates, Map<UUID, RegionDefinition> regions) {
        if (policy == null || policy.partialBoxDestinationRef() == null) return;
        var ref = ResourceReference.parse(policy.partialBoxDestinationRef());
        if (ref.type() == ResourceType.COORDINATE && !coordinates.containsKey(ref.uuid())
                || ref.type() == ResourceType.REGION && !regions.containsKey(ref.uuid())) {
            throw new V2ValidationException("未满盒储存位置引用的坐标或区域不存在");
        }
    }

    private static void requireWarehouseDestination(String destination,
            Map<UUID, CoordinatePoint> coordinates, Map<UUID, RegionDefinition> regions) {
        if (destination == null) return;
        var ref = ResourceReference.parse(destination);
        boolean exists = switch (ref.type()) {
            case COORDINATE -> coordinates.containsKey(ref.uuid());
            case REGION -> regions.containsKey(ref.uuid());
            case WAREHOUSE -> true;
            default -> false;
        };
        if (!exists) throw new V2ValidationException("溢出存入位置引用的坐标或区域不存在");
    }

    private static void requireAutoTask(
            Map<UUID, TaskDefinition> tasks,
            UUID id,
            TaskInterruptionMode interruptionMode
    ) {
        TaskDefinition task = tasks.get(id);
        if (task == null) {
            throw new V2ValidationException(
                    "auto classify derived task is missing: " + id
            );
        }
        if (task.interruptionMode() != interruptionMode) {
            throw new V2ValidationException(
                    "auto classify derived task has invalid interruption mode"
            );
        }
    }

    private static void requireInterruption(
            TaskDefinition task,
            Map<UUID, TaskDefinition> tasks
    ) {
        if (task.interruptionMode() != TaskInterruptionMode.CUSTOM) {
            return;
        }
        ResourceReference reference = ResourceReference.parse(
                task.interruptionTaskRef()
        );
        if (reference.type() != ResourceType.TASK) {
            throw new V2ValidationException(
                    "interruption reference must target a task: " + reference
            );
        }
        TaskDefinition interruptionTask = tasks.get(reference.uuid());
        if (interruptionTask == null) {
            throw new V2ValidationException(
                    "interruption task does not exist: " + reference
            );
        }
        if (containsTaskCreation(interruptionTask.steps())) {
            throw new V2ValidationException(
                    "interruption tasks cannot create tasks: " + reference
            );
        }
    }

    private static boolean containsTaskCreation(List<TaskStep> steps) {
        for (TaskStep step : steps) {
            if (step.kind() == TaskStepKind.CREATE_TASK
                    || containsTaskCreation(step.steps())
                    || containsTaskCreation(step.otherwise())) {
                return true;
            }
        }
        return false;
    }

    private static void validateTriggers(
            List<TaskTriggerDefinition> triggers,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, TaskDefinition> tasks
    ) {
        Set<UUID> triggerIds = new HashSet<>();
        Set<UUID> scheduledTasks = new HashSet<>();
        for (TaskTriggerDefinition trigger : triggers) {
            if (!triggerIds.add(trigger.id())) {
                throw new V2ValidationException("duplicate task trigger id");
            }
            if (!tasks.containsKey(trigger.targetTaskId())) {
                throw new V2ValidationException(
                        "target task does not exist: "
                                + trigger.targetTaskId()
                );
            }
            if (trigger.schedule() != null
                    && (!dev.stowling.v2.model.ContainerCacheRefresh.isManualTask(
                            tasks.get(trigger.targetTaskId()))
                    || !scheduledTasks.add(trigger.targetTaskId()))) {
                throw new V2ValidationException("每个刷新缓存任务只能配置一条定时规则");
            }
            if (trigger.coordinateId() != null
                    && !coordinates.containsKey(trigger.coordinateId())) {
                throw new V2ValidationException(
                        "trigger coordinate does not exist: "
                                + trigger.coordinateId()
                );
            }
            if (trigger.cacheRegionId() != null
                    && !regions.containsKey(trigger.cacheRegionId())) {
                throw new V2ValidationException(
                        "trigger cache region does not exist: "
                                + trigger.cacheRegionId()
                );
            }
        }
    }

    public static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireValidStep(step, coordinates, Map.of(), Map.of(), Set.of());
    }

    public static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions
    ) {
        requireValidStep(step, coordinates, regions, Map.of(), Set.of());
    }

    public static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        requireValidStep(step, coordinates, regions, categoryZones, Set.of());
    }

    private static void requireValidStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            Set<UUID> taskIds
    ) {
        switch (step.kind()) {
            case IF -> requireConditionalStep(
                    step,
                    coordinates,
                    regions,
                    categoryZones,
                    taskIds,
                    false
            );
            case REPEAT -> requireConditionalStep(
                    step,
                    coordinates,
                    regions,
                    categoryZones,
                    taskIds,
                    true
            );
            case CREATE_TASK -> requireCreateTaskStep(step, taskIds);
            case ACTION -> requireActionStep(
                    step,
                    coordinates,
                    regions,
                    categoryZones
            );
        }
    }

    private static void requireConditionalStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            Set<UUID> taskIds,
            boolean repeat
    ) {
        Set<String> allowed = repeat
                ? Set.of(
                        TaskFlowTemplates.CONDITION,
                        TaskFlowTemplates.NEGATE,
                        FOOD_LEVEL_THRESHOLD,
                        FOOD_ITEM_ID,
                        TaskFlowTemplates.MAX_ITERATIONS,
                        ITEM_SOURCE
                )
                : Set.of(
                        TaskFlowTemplates.CONDITION,
                        TaskFlowTemplates.NEGATE,
                        FOOD_LEVEL_THRESHOLD,
                        FOOD_ITEM_ID,
                        ITEM_SOURCE
                );
        requireParameters(step, allowed, Set.of(TaskFlowTemplates.CONDITION));
        TaskCondition condition;
        try {
            condition = TaskCondition.parse(
                    step.parameters().get(TaskFlowTemplates.CONDITION)
            );
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException("task condition is not supported");
        }
        if (condition == TaskCondition.FOOD_BELOW_THRESHOLD) {
            requireRequiredInteger(step, FOOD_LEVEL_THRESHOLD, 0, 20);
        } else if (step.parameters().containsKey(FOOD_LEVEL_THRESHOLD)) {
            throw new V2ValidationException(
                    FOOD_LEVEL_THRESHOLD + " only applies to food conditions"
            );
        }
        if (condition == TaskCondition.HAS_PROTECTED_FOOD) {
            requireFoodItemId(step);
        } else if (step.parameters().containsKey(FOOD_ITEM_ID)) {
            throw new V2ValidationException(FOOD_ITEM_ID + " only applies to HAS_PROTECTED_FOOD");
        }
        if (condition == TaskCondition.HAS_MANAGED_ITEMS) {
            requireOptionalChoiceIn(
                    step,
                    ITEM_SOURCE,
                    Set.of("collected", "carried", "managed")
            );
        } else if (step.parameters().containsKey(ITEM_SOURCE)) {
            throw new V2ValidationException(
                    ITEM_SOURCE + " only applies to HAS_MANAGED_ITEMS"
            );
        }
        requireOptionalBoolean(step, TaskFlowTemplates.NEGATE);
        if (repeat) {
            requireOptionalInteger(
                    step,
                    TaskFlowTemplates.MAX_ITERATIONS,
                    1,
                    1_000_000
            );
            if (!step.otherwise().isEmpty()) {
                throw new V2ValidationException(
                        "repeat steps cannot define otherwise"
                );
            }
        }
        if (step.steps().isEmpty()) {
            throw new V2ValidationException("conditional steps require a body");
        }
        for (TaskStep nested : step.steps()) {
            requireValidStep(
                    nested,
                    coordinates,
                    regions,
                    categoryZones,
                    taskIds
            );
        }
        for (TaskStep nested : step.otherwise()) {
            requireValidStep(
                    nested,
                    coordinates,
                    regions,
                    categoryZones,
                    taskIds
            );
        }
    }

    private static void requireCreateTaskStep(
            TaskStep step,
            Set<UUID> taskIds
    ) {
        if (!step.steps().isEmpty() || !step.otherwise().isEmpty()) {
            throw new V2ValidationException(
                    "create-task steps cannot contain nested steps"
            );
        }
        requireParameters(
                step,
                Set.of(TASK_REF, PRIORITY, CREATE_AFTER_SUCCESS),
                Set.of(TASK_REF)
        );
        requireOptionalBoolean(step, CREATE_AFTER_SUCCESS);
        ResourceReference reference = requireReference(
                step,
                TASK_REF,
                ResourceType.TASK
        );
        if (!taskIds.isEmpty() && !taskIds.contains(reference.uuid())) {
            throw new V2ValidationException(
                    "task reference does not exist: " + reference
            );
        }
        String priority = step.parameters().get(PRIORITY);
        if (priority != null
                && !Set.of("BACKGROUND", "NORMAL", "URGENT").contains(priority)) {
            throw new V2ValidationException("task priority is not supported");
        }
    }

    private static void requireActionStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        if (!step.steps().isEmpty() || !step.otherwise().isEmpty()) {
            throw new V2ValidationException(
                    "action steps cannot contain nested steps"
            );
        }
        switch (step.moduleId()) {
            case "MANAGE_CACHE_INTAKE" -> {
                requireParameters(step, Set.of("cacheIntakeMode", "cacheThresholdPercent",
                        "classificationTaskRef"), Set.of("cacheIntakeMode",
                        "cacheThresholdPercent"));
                new dev.stowling.v2.model.CacheIntakePolicy(
                        dev.stowling.v2.model.CacheIntakePolicy.Mode.valueOf(
                                step.parameters().get("cacheIntakeMode")),
                        Integer.parseInt(step.parameters().get("cacheThresholdPercent")));
                String taskRef = step.parameters().get("classificationTaskRef");
                if (taskRef != null && !taskRef.isBlank()
                        && ResourceReference.parse(taskRef).type() != ResourceType.TASK) {
                    throw new V2ValidationException("缓存分类需要引用任务");
                }
            }
            case PACK_OVERFLOW, ROBOT_WAREHOUSE -> {
                requireParameters(step, ROBOT_WAREHOUSE.equals(step.moduleId())
                        ? Set.of(REGION_REF, CACHE_LOCATION_REF, TARGET_REF) : Set.of(REGION_REF), Set.of());
                if (step.parameters().containsKey(REGION_REF)) {
                    requireRegionReference(step, regions, REGION_REF);
                }
                requireSpatialReference(step.parameters().get(CACHE_LOCATION_REF),
                        coordinates, regions);
                requireWarehouseDestination(step.parameters().get(TARGET_REF), coordinates, regions);
            }
            case CONTAINER_INTERACTION -> requireInteractionStep(
                    step,
                    coordinates,
                    regions,
                    categoryZones
            );
            case MOVE_TO_COORDINATE -> requireMoveStep(step, coordinates);
            case dev.stowling.v2.model.ContainerCacheRefresh.MODULE -> {
                var keys = dev.stowling.v2.model.ContainerCacheRefresh.BOOLEAN_FIELDS;
                Set<String> fields = new HashSet<>(keys);
                fields.add(TARGET_REF);
                requireParameters(step, fields, Set.of(TARGET_REF));
                var refresh = dev.stowling.v2.model.ContainerCacheRefresh.from(step.parameters());
                if (refresh.target().type() == ResourceType.CATEGORY_ZONE && !refresh.markerOnly()) {
                    throw new V2ValidationException("刷新缓存任务请选择普通区域");
                }
                requireInteractionResource(refresh.target().encode(), coordinates, regions,
                        categoryZones);
            }
            case dev.stowling.v2.model.MaterialRequirements.MODULE -> {
                var materials = dev.stowling.v2.model.MaterialRequirements.PARAMETER;
                var boxedDelivery = dev.stowling.v2.model.MaterialRequirements.BOXED_DELIVERY;
                var singleMaterialBoxes =
                        dev.stowling.v2.model.MaterialRequirements.SINGLE_MATERIAL_BOXES;
                var iceInsteadOfWater =
                        dev.stowling.v2.model.MaterialRequirements.ICE_INSTEAD_OF_WATER;
                var skipWater = dev.stowling.v2.model.MaterialRequirements.SKIP_WATER_BUCKETS;
                var skipLava = dev.stowling.v2.model.MaterialRequirements.SKIP_LAVA;
                var collectShulkerBoxes =
                        dev.stowling.v2.model.MaterialRequirements.COLLECT_SHULKER_BOXES;
                var collectMinecarts =
                        dev.stowling.v2.model.MaterialRequirements.COLLECT_MINECARTS;
                var sourceZone = dev.stowling.v2.model.MaterialRequirements.SOURCE_ZONE_REF;
                requireParameters(step, Set.of(TARGET_REF, sourceZone, materials, boxedDelivery,
                        singleMaterialBoxes, iceInsteadOfWater, skipWater, skipLava,
                        collectShulkerBoxes, collectMinecarts),
                        Set.of(TARGET_REF, materials));
                requireOptionalBoolean(step, boxedDelivery);
                requireOptionalBoolean(step, singleMaterialBoxes);
                requireOptionalBoolean(step, iceInsteadOfWater);
                requireOptionalBoolean(step, skipWater);
                requireOptionalBoolean(step, skipLava);
                requireOptionalBoolean(step, collectShulkerBoxes);
                requireOptionalBoolean(step, collectMinecarts);
                String destination = step.parameters().get(TARGET_REF);
                var delivery = ResourceReference.parse(destination);
                if (delivery.type() != ResourceType.COORDINATE && delivery.type() != ResourceType.REGION) {
                    throw new V2ValidationException("材料交付位置必须是点位或普通区域");
                }
                if (step.parameters().containsKey(sourceZone)) {
                    var ref = requireReference(step, sourceZone, ResourceType.CATEGORY_ZONE);
                    if (!categoryZones.containsKey(ref.uuid())) {
                        throw new V2ValidationException("材料取料分类区不存在");
                    }
                }
                requireInteractionResource(destination, coordinates, regions, categoryZones);
                dev.stowling.v2.model.MaterialRequirements.forCollection(step.parameters());
            }
            case COLLECT_DROPPED_ITEMS -> requireCollectStep(
                    step,
                    coordinates,
                    regions
            );
            case SCAN_CONTAINERS_IN_REGION -> requireScanStep(step, coordinates, regions);
            case RESOLVE_CONTAINER_CATEGORIES ->
                    requireResolveCategoryStep(step, categoryZones);
            case RESOLVE_UNSTACKABLE_STORAGE -> {
                requireParameters(step, Set.of(REGION_REF, CONTAINER_TYPES), Set.of(REGION_REF));
                requireRegionReference(step, regions, REGION_REF);
            }
            case SORT_UNSTACKABLE_ITEMS -> {
                var fields = Set.of(REGION_REF, TARGET_REF);
                requireParameters(step, fields, fields);
                requireRegionReference(step, regions, REGION_REF);
                requireRegionReference(step, regions, TARGET_REF);
                if (step.parameters().get(REGION_REF).equals(step.parameters().get(TARGET_REF))) {
                    throw new V2ValidationException("不可堆叠储存区需要使用独立区域");
                }
            }
            case SELECT_CONTAINER -> requireParameters(
                    step,
                    Set.of(TaskFlowTemplates.RESET_CACHE_HAS_MORE),
                    Set.of()
            );
            case WITHDRAW_ITEMS -> requireWithdrawStep(step, coordinates);
            case SELECT_STORAGE_TARGET -> {
                requireParameters(
                        step,
                        Set.of(ITEM_SOURCE, ALLOW_CACHE_FALLBACK),
                        Set.of()
                );
                requireOptionalChoiceIn(
                        step,
                        ITEM_SOURCE,
                        Set.of("collected", "carried", "managed")
                );
                requireOptionalBoolean(step, ALLOW_CACHE_FALLBACK);
            }
            case ASSERT_NO_MANAGED_ITEMS -> {
                requireParameters(step, Set.of(ITEM_SOURCE), Set.of());
                requireOptionalChoiceIn(
                        step,
                        ITEM_SOURCE,
                        Set.of("collected", "carried", "managed")
                );
            }
            case ASSERT_PLAYER_INVENTORY_RESTORED ->
                    requireParameters(step, Set.of(), Set.of());
            case SYNC_COLLECTED_INVENTORY, SYNC_PLAYER_INVENTORY -> requireParameters(
                    step,
                    Set.of(ITEM_FILTER),
                    Set.of()
            );
            case DEPOSIT_ITEMS_TO_CONTAINERS ->
                    requireDepositStep(step, coordinates, regions);
            case WITHDRAW_FOOD -> requireWithdrawFoodStep(step, coordinates);
            case EAT_FOOD -> requireEatFoodStep(step);
            case SCAN_CATEGORY_MARKERS, SAVE_CATEGORY_MARKER_CACHE ->
                    requireMarkerZoneStep(step, categoryZones);
            case SELECT_MARKER_CONTAINER ->
                    requireParameters(step, Set.of(), Set.of());
            case READ_CONTAINER -> requireReadContainerStep(
                    step,
                    coordinates
            );
            case WAIT_FOR_DROPPED_ITEMS -> requireLegacyWaitStep(
                    step,
                    coordinates
            );
            case SORT_CACHE_ITEMS -> throw new V2ValidationException(
                    "SORT_CACHE_ITEMS requires an explicit category zone"
            );
            case COLLECT_DROPPED_ITEMS_IN_REGION ->
                    requireLegacyRegionCollectStep(step, regions);
            case AUTO_EAT_AT_COORDINATE -> requireLegacyAutoEatStep(
                    step,
                    coordinates
            );
            case SORT_CACHE_ITEMS_BY_CATEGORY_ZONE ->
                    requireLegacyCategoryZoneSortStep(
                            step,
                            regions,
                            categoryZones
                    );
            case REFRESH_CATEGORY_MARKER_CACHE ->
                    requireMarkerZoneStep(step, categoryZones);
            default -> throw new V2ValidationException(
                    "unknown task module: " + step.moduleId()
            );
        }
    }

    private static void requireInteractionStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        try {
            TaskInteractionCompiler.validateCompactStep(step);
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(safeMessage(exception));
        }
        String operation = step.parameters().get(
                TaskInteractionCompiler.OPERATION
        ).trim();
        switch (operation) {
            case "COLLECT" -> {
                String area = step.parameters().getOrDefault(AREA_REF,
                        "detector");
                if (!"detector".equals(area)) {
                    requireInteractionResource(
                            area,
                            coordinates,
                            regions,
                            categoryZones
                    );
                }
            }
            case "FIND", "DEPOSIT" -> requireInteractionResource(
                    step.parameters().get(TARGET_REF),
                    coordinates,
                    regions,
                    categoryZones
            );
            case "READ", "WITHDRAW" -> requireInteractionResource(
                    step.parameters().get(TARGET_REF),
                    coordinates,
                    regions,
                    categoryZones
            );
            default -> throw new V2ValidationException(
                    "unsupported container interaction operation"
            );
        }
    }

    private static void requireInteractionResource(
            String value,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        ResourceReference reference = ResourceReference.parse(value);
        switch (reference.type()) {
            case COORDINATE -> {
                if (!coordinates.containsKey(reference.uuid())) {
                    throw new V2ValidationException(
                            "coordinate does not exist: " + reference
                    );
                }
            }
            case REGION -> {
                if (!regions.containsKey(reference.uuid())) {
                    throw new V2ValidationException(
                            "region does not exist: " + reference
                    );
                }
            }
            case CATEGORY_ZONE -> {
                if (!categoryZones.containsKey(reference.uuid())) {
                    throw new V2ValidationException(
                            "category zone does not exist: " + reference
                    );
                }
            }
            case WAREHOUSE -> throw new V2ValidationException("此设置无法使用仓库");
            case OUTPUT, TASK -> {
            }
        }
    }

    private static void validateCompiledInteraction(
            TaskDefinition task,
            List<TaskTriggerDefinition> triggers,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            Set<UUID> taskIds
    ) {
        if (!containsInteraction(task.steps())) {
            return;
        }
        final TaskDefinition compiled;
        try {
            compiled = TaskInteractionCompiler.compile(task, triggers);
        } catch (TaskInteractionCompiler.DetectorNotBoundException exception) {
            return;
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(safeMessage(exception));
        }
        for (TaskStep step : compiled.steps()) {
            requireValidStep(
                    step,
                    coordinates,
                    regions,
                    categoryZones,
                    taskIds
            );
        }
    }

    private static boolean containsInteraction(List<TaskStep> steps) {
        for (TaskStep step : steps) {
            if (CONTAINER_INTERACTION.equals(step.moduleId())
                    || SORT_UNSTACKABLE_ITEMS.equals(step.moduleId())
                    || containsInteraction(step.steps())
                    || containsInteraction(step.otherwise())) {
                return true;
            }
        }
        return false;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static void requireResolveCategoryStep(
            TaskStep step,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        requireParameters(
                step,
                Set.of(
                        CATEGORY_ZONE_REF,
                        CATEGORY_ZONE_ID,
                        CATEGORY_BINDING_ID,
                        CATEGORY_RULE_ID,
                        CONTAINER_TYPES,
                        MAX_VOLUME,
                        ALLOW_EMPTY_CONTAINER_SCAN,
                        MARKER_SOURCES
                ),
                Set.of()
        );
        requireCategoryZoneReference(step, categoryZones);
        requireCategorySelectionStep(step, categoryZones);
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
        requireOptionalBoolean(step, ALLOW_EMPTY_CONTAINER_SCAN);
        requireOptionalMarkerSources(step);
    }

    private static void requireCoordinateStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Set<String> allowed,
            Set<String> required
    ) {
        requireParameters(step, allowed, required);
        requireCoordinateReference(step, coordinates);
        requireOptionalInteger(step, TIMEOUT_SECONDS, 0, 86_400);
    }

    private static void requireMoveStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireParameters(
                step,
                Set.of(TARGET_REF, COORDINATE_ID, X, Y, Z, INTERACT_WITH_TARGET),
                Set.of()
        );
        requireOptionalBoolean(step, INTERACT_WITH_TARGET);
        if (!Collections.disjoint(
                step.parameters().keySet(),
                MANUAL_COORDINATE_KEYS
        )) {
            if (step.parameters().containsKey(INTERACT_WITH_TARGET)) {
                throw new V2ValidationException("interactWithTarget requires a coordinate ref");
            }
            requireManualMoveCoordinates(step);
            return;
        }
        ResourceReference reference = referenceParameter(
                step,
                ResourceType.COORDINATE,
                TARGET_REF,
                COORDINATE_ID
        );
        if (reference.type() == ResourceType.OUTPUT) {
            return;
        }
        if (reference.type() != ResourceType.COORDINATE
                || !coordinates.containsKey(reference.uuid())) {
            throw new V2ValidationException(
                    "move target does not exist: " + reference
            );
        }
    }

    private static void requireManualMoveCoordinates(TaskStep step) {
        if (step.parameters().containsKey(TARGET_REF)
                || step.parameters().containsKey(COORDINATE_ID)) {
            throw new V2ValidationException(
                    "manual move coordinates cannot be combined with a reference"
            );
        }
        for (String key : MANUAL_COORDINATE_KEYS) {
            String value = step.parameters().get(key);
            if (value == null || value.isBlank()) {
                throw new V2ValidationException(
                        "manual move coordinates require x, y and z"
                );
            }
        }
        for (String key : MANUAL_COORDINATE_KEYS) {
            requireRequiredInteger(
                    step,
                    key,
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE
            );
        }
    }

    private static void requireCoordinateReference(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        ResourceReference reference = referenceParameter(
                step,
                ResourceType.COORDINATE,
                TARGET_REF,
                COORDINATE_ID
        );
        if (reference.type() != ResourceType.COORDINATE
                || !coordinates.containsKey(reference.uuid())) {
            throw new V2ValidationException(
                    "coordinate does not exist: " + reference
            );
        }
    }

    private static void requireCollectStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions
    ) {
        requireParameters(
                step,
                Set.of(
                        AREA_REF,
                        AREA_REFERENCE,
                        COORDINATE_ID,
                        REGION_ID,
                        ITEM_FILTER,
                        TIMEOUT_SECONDS,
                        PICKUP_RADIUS, DEFAULT_PICKUP_RADIUS
                ),
                Set.of()
        );
        ResourceReference area = areaReference(step);
        requireAreaExists(area, coordinates, regions);
        requireOptionalInteger(step, DEFAULT_PICKUP_RADIUS, 1,
                AutoClassifyDefinition.MAX_PICKUP_RADIUS);
        requireOptionalInteger(step, TIMEOUT_SECONDS, 0, 86_400);
        requireOptionalInteger(
                step,
                PICKUP_RADIUS,
                AutoClassifyDefinition.MIN_PICKUP_RADIUS,
                AutoClassifyDefinition.MAX_PICKUP_RADIUS
        );
    }

    private static void requireScanStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions
    ) {
        requireParameters(
                step,
                Set.of(REGION_REF, REGION_ID, CACHE_REGION_ID, CACHE_LOCATION_REF,
                        CONTAINER_TYPES, MAX_VOLUME, SCAN_CONTENTS),
                Set.of()
        );
        String cache = step.parameters().get(CACHE_LOCATION_REF);
        if (cache != null) {
            requireSpatialReference(cache, coordinates, regions);
        } else {
            requireRegionReference(step, regions, REGION_REF, REGION_ID, CACHE_REGION_ID);
        }
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
        requireOptionalBoolean(step, SCAN_CONTENTS);
    }

    private static void requireCategoryZoneStep(
            TaskStep step,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        requireParameters(
                step,
                Set.of(CATEGORY_ZONE_REF, CATEGORY_ZONE_ID),
                Set.of()
        );
        requireCategoryZoneReference(step, categoryZones);
    }

    private static void requireWithdrawStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireParameters(
                step,
                Set.of(TARGET_REF, ITEM_SOURCE, RETRY_ON_FAILURE),
                Set.of(TARGET_REF, ITEM_SOURCE)
        );
        requireContainerReference(step, coordinates);
        requireChoice(step, ITEM_SOURCE, "classified");
    }

    private static void requireReadContainerStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireParameters(step, Set.of(TARGET_REF), Set.of(TARGET_REF));
        ResourceReference reference = ResourceReference.parse(
                step.parameters().get(TARGET_REF)
        );
        if (reference.type() == ResourceType.COORDINATE) {
            if (!coordinates.containsKey(reference.uuid())) {
                throw new V2ValidationException(
                        "coordinate does not exist: " + reference
                );
            }
            return;
        }
        if (reference.type() != ResourceType.OUTPUT) {
            throw new V2ValidationException(
                    TARGET_REF + " must reference a coordinate or output"
            );
        }
    }

    private static void requireDepositStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions
    ) {
        if (!step.parameters().containsKey(TARGET_REF)) {
            requireParameters(
                    step,
                    Set.of(SCAN_SOURCE, REGION_REF, REGION_ID,
                            CACHE_REGION_ID, ITEM_SOURCE, FILL_MODE),
                    Set.of()
            );
            if (step.parameters().containsKey(REGION_REF)
                    || step.parameters().containsKey(REGION_ID)
                    || step.parameters().containsKey(CACHE_REGION_ID)) {
                requireRegionReference(
                        step,
                        regions,
                        REGION_REF,
                        REGION_ID,
                        CACHE_REGION_ID
                );
            }
            return;
        }
        requireParameters(
                step,
                Set.of(TARGET_REF, ITEM_SOURCE, RETRY_ON_FAILURE),
                Set.of(TARGET_REF, ITEM_SOURCE)
        );
        requireContainerReference(step, coordinates);
        requireChoiceIn(
                step,
                ITEM_SOURCE,
                Set.of("collected", "carried", "managed")
        );
        requireOptionalBoolean(step, RETRY_ON_FAILURE);
    }

    private static void requireWithdrawFoodStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireParameters(
                step,
                Set.of(TARGET_REF, COORDINATE_ID, FOOD_ITEM_ID, RESERVE_FOOD),
                Set.of(FOOD_ITEM_ID)
        );
        requireCoordinateReference(step, coordinates);
        requireFoodItemId(step);
        requireOptionalBoolean(step, RESERVE_FOOD);
    }

    private static void requireEatFoodStep(TaskStep step) {
        requireParameters(
                step,
                Set.of(FOOD_ITEM_ID, ITEM_SOURCE),
                Set.of(FOOD_ITEM_ID)
        );
        requireFoodItemId(step);
        String source = step.parameters().get(ITEM_SOURCE);
        if (source != null && !Set.of("protected", "managed").contains(source)) {
            throw new V2ValidationException("food itemSource must be protected or managed");
        }
    }

    private static void requireMarkerZoneStep(
            TaskStep step,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        requireParameters(
                step,
                Set.of(CATEGORY_ZONE_REF, CATEGORY_ZONE_ID, MAX_VOLUME,
                        MARKER_SOURCES, CATEGORY_BINDING_ID,
                        CATEGORY_RULE_ID),
                Set.of()
        );
        requireCategoryZoneReference(step, categoryZones);
        requireCategorySelectionStep(step, categoryZones);
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
        requireOptionalMarkerSources(step);
    }

    private static void requireLegacyWaitStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireParameters(
                step,
                Set.of(TARGET_REF, COORDINATE_ID, ITEM_FILTER, TIMEOUT_SECONDS),
                Set.of()
        );
        requireCoordinateReference(step, coordinates);
        requireOptionalInteger(step, TIMEOUT_SECONDS, 0, 86_400);
    }

    private static void requireLegacySortStep(
            TaskStep step,
            Map<UUID, RegionDefinition> regions
    ) {
        requireParameters(
                step,
                Set.of(REGION_REF, CACHE_REGION_ID, SCAN_SOURCE,
                        CONTAINER_TYPES, MAX_VOLUME),
                Set.of()
        );
        requireRegionReference(step, regions, REGION_REF, CACHE_REGION_ID);
        if (step.parameters().containsKey(SCAN_SOURCE)) {
            requireChoice(step, SCAN_SOURCE, "previous-scan");
        }
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
    }

    private static void requireLegacyRegionCollectStep(
            TaskStep step,
            Map<UUID, RegionDefinition> regions
    ) {
        requireParameters(
                step,
                Set.of(REGION_REF, REGION_ID, ITEM_FILTER, TIMEOUT_SECONDS,
                        CONTAINER_TYPES, MAX_VOLUME),
                Set.of()
        );
        requireRegionReference(step, regions, REGION_REF, REGION_ID);
        requireOptionalInteger(step, TIMEOUT_SECONDS, 0, 86_400);
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
    }

    private static void requireLegacyAutoEatStep(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        requireParameters(
                step,
                Set.of(TARGET_REF, COORDINATE_ID, FOOD_ITEM_ID,
                        FOOD_LEVEL_THRESHOLD),
                Set.of(FOOD_ITEM_ID)
        );
        requireCoordinateReference(step, coordinates);
        requireFoodItemId(step);
        requireOptionalInteger(step, FOOD_LEVEL_THRESHOLD, 0, 20);
    }

    private static void requireLegacyCategoryZoneSortStep(
            TaskStep step,
            Map<UUID, RegionDefinition> regions,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        requireParameters(
                step,
                Set.of(REGION_REF, CACHE_REGION_ID, CATEGORY_ZONE_REF,
                        CATEGORY_ZONE_ID, CONTAINER_TYPES, MAX_VOLUME),
                Set.of()
        );
        requireRegionReference(step, regions, REGION_REF, CACHE_REGION_ID);
        requireCategoryZoneReference(step, categoryZones);
        requireOptionalInteger(step, MAX_VOLUME, 1, 1_048_576);
    }

    private static void requireFoodItemId(TaskStep step) {
        String foodItemId = step.parameters().get(FOOD_ITEM_ID);
        if (foodItemId == null || !foodItemId.trim().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new V2ValidationException(
                    FOOD_ITEM_ID + " must be a resource location"
            );
        }
    }

    private static void requireAreaExists(
            ResourceReference area,
            Map<UUID, CoordinatePoint> coordinates,
            Map<UUID, RegionDefinition> regions
    ) {
        if (area.type() == ResourceType.COORDINATE) {
            if (!coordinates.containsKey(area.uuid())) {
                throw new V2ValidationException(
                        "coordinate does not exist: " + area
                );
            }
        } else if (area.type() == ResourceType.REGION) {
            if (!regions.containsKey(area.uuid())) {
                throw new V2ValidationException("region does not exist: " + area);
            }
        } else {
            throw new V2ValidationException(
                    "collection area must be a coordinate or region"
            );
        }
    }

    private static ResourceReference areaReference(TaskStep step) {
        String area = step.parameters().get(AREA_REF);
        if (area == null) {
            area = step.parameters().get(AREA_REFERENCE);
        }
        if (area != null) {
            ResourceReference reference = ResourceReference.parse(area);
            if (reference.type() != ResourceType.COORDINATE
                    && reference.type() != ResourceType.REGION) {
                throw new V2ValidationException(
                        "area must reference a coordinate or region"
                );
            }
            return reference;
        }
        String coordinate = step.parameters().get(COORDINATE_ID);
        if (coordinate != null) {
            return reference(ResourceType.COORDINATE, coordinate);
        }
        String region = step.parameters().get(REGION_ID);
        if (region != null) {
            return reference(ResourceType.REGION, region);
        }
        throw new V2ValidationException("collection area is required");
    }

    private static void requireRegionReference(
            TaskStep step,
            Map<UUID, RegionDefinition> regions,
            String... keys
    ) {
        ResourceReference reference = referenceParameter(
                step,
                ResourceType.REGION,
                keys
        );
        if (reference.type() != ResourceType.REGION
                || !regions.containsKey(reference.uuid())) {
            throw new V2ValidationException(
                    "region does not exist: " + reference
            );
        }
    }

    private static void requireCategoryZoneReference(
            TaskStep step,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        ResourceReference reference = referenceParameter(
                step,
                ResourceType.CATEGORY_ZONE,
                CATEGORY_ZONE_REF,
                CATEGORY_ZONE_ID
        );
        if (reference.type() != ResourceType.CATEGORY_ZONE
                || !categoryZones.containsKey(reference.uuid())) {
            throw new V2ValidationException(
                    "category zone does not exist: " + reference
            );
        }
    }

    private static void requireCategorySelectionStep(
            TaskStep step,
            Map<UUID, CategoryZoneDefinition> categoryZones
    ) {
        String bindingValue = step.parameters().get(CATEGORY_BINDING_ID);
        if (bindingValue != null && !bindingValue.isBlank()) {
            UUID bindingId;
            try {
                bindingId = UUID.fromString(bindingValue.trim());
            } catch (IllegalArgumentException exception) {
                throw new V2ValidationException(
                        "categoryBindingId must be a UUID"
                );
            }
            ResourceReference zoneReference = referenceParameter(
                    step,
                    ResourceType.CATEGORY_ZONE,
                    CATEGORY_ZONE_REF,
                    CATEGORY_ZONE_ID
            );
            CategoryZoneDefinition zone = categoryZones.get(
                    zoneReference.uuid()
            );
            if (zone == null || zone.bindings().stream().noneMatch(
                    binding -> binding.id().equals(bindingId)
            )) {
                throw new V2ValidationException(
                        "category binding does not exist"
                );
            }
        }
        String ruleId = step.parameters().get(CATEGORY_RULE_ID);
        if (ruleId != null && !ruleId.isBlank()) {
            CategoryRuleRegistry.standard().require(ruleId.trim());
        }
    }

    private static ResourceReference requireReference(
            TaskStep step,
            String key,
            ResourceType expected
    ) {
        String value = step.parameters().get(key);
        if (value == null || value.isBlank()) {
            throw new V2ValidationException(key + " is required");
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() != expected) {
            throw new V2ValidationException(
                    key + " must reference " + expected
            );
        }
        return reference;
    }

    private static void requireContainerReference(
            TaskStep step,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        String value = step.parameters().get(TARGET_REF);
        if (value == null || value.isBlank()) {
            throw new V2ValidationException(TARGET_REF + " is required");
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() == ResourceType.OUTPUT) {
            return;
        }
        if (reference.type() != ResourceType.COORDINATE
                || !coordinates.containsKey(reference.uuid())) {
            throw new V2ValidationException(
                    "container reference must be a coordinate or output"
            );
        }
    }

    private static ResourceReference referenceParameter(
            TaskStep step,
            ResourceType fallback,
            String... keys
    ) {
        for (String key : keys) {
            String value = step.parameters().get(key);
            if (value != null && !value.isBlank()) {
                return reference(fallback, value);
            }
        }
        throw new V2ValidationException(keys[0] + " is required");
    }

    private static ResourceReference reference(
            ResourceType fallback,
            String value
    ) {
        try {
            return value.contains(":")
                    ? ResourceReference.parse(value)
                    : new ResourceReference(fallback, value);
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException("invalid resource reference");
        }
    }

    private static void requireParameters(
            TaskStep step,
            Set<String> allowed,
            Set<String> required
    ) {
        if (!allowed.containsAll(step.parameters().keySet())
                || !step.parameters().keySet().containsAll(required)) {
            throw new V2ValidationException(
                    step.moduleId() + " has invalid parameters"
            );
        }
    }

    private static void requireRequiredInteger(
            TaskStep step,
            String key,
            int minimum,
            int maximum
    ) {
        if (!step.parameters().containsKey(key)) {
            throw new V2ValidationException(key + " is required");
        }
        requireOptionalInteger(step, key, minimum, maximum);
    }

    private static void requireOptionalInteger(
            TaskStep step,
            String key,
            int minimum,
            int maximum
    ) {
        String value = step.parameters().get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new V2ValidationException(
                        key + " is outside the supported range"
                );
            }
        } catch (NumberFormatException exception) {
            throw new V2ValidationException(key + " must be an integer");
        }
    }

    private static void requireOptionalBoolean(TaskStep step, String key) {
        String value = step.parameters().get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new V2ValidationException(key + " must be a boolean");
        }
    }

    private static void requireChoiceIn(
            TaskStep step,
            String key,
            Set<String> choices
    ) {
        if (!choices.contains(step.parameters().get(key))) {
            throw new V2ValidationException(key + " is not supported");
        }
    }

    private static void requireChoice(
            TaskStep step,
            String key,
            String expected
    ) {
        if (!expected.equals(step.parameters().get(key))) {
            throw new V2ValidationException(key + " is not supported");
        }
    }

    private static void requireOptionalChoiceIn(
            TaskStep step,
            String key,
            Set<String> choices
    ) {
        String value = step.parameters().get(key);
        if (value != null && !choices.contains(value)) {
            throw new V2ValidationException(key + " is not supported");
        }
    }

    private static void requireOptionalMarkerSources(TaskStep step) {
        String value = step.parameters().get(MARKER_SOURCES);
        if (value == null || value.isBlank()) {
            return;
        }
        Set<CategoryMarkerSource> parsed = new HashSet<>();
        for (String part : value.split(",")) {
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                throw new V2ValidationException(
                        MARKER_SOURCES + " contains an empty source"
                );
            }
            try {
                parsed.add(CategoryMarkerSource.valueOf(candidate));
            } catch (IllegalArgumentException exception) {
                throw new V2ValidationException(
                        MARKER_SOURCES + " contains an unknown source"
                );
            }
        }
        if (parsed.isEmpty()) {
            throw new V2ValidationException(
                    MARKER_SOURCES + " must contain at least one source"
            );
        }
    }

    private static Map<UUID, CategoryZoneDefinition> validateCategoryZones(
            List<CategoryZoneDefinition> categoryZones
    ) {
        CategoryRuleRegistry rules = CategoryRuleRegistry.standard();
        Map<UUID, CategoryZoneDefinition> result = new LinkedHashMap<>();
        Set<UUID> bindingIds = new HashSet<>();
        Set<UUID> overrideIds = new HashSet<>();
        List<AreaBounds> storageAreas = new ArrayList<>();
        for (int zoneIndex = 0; zoneIndex < categoryZones.size(); zoneIndex++) {
            CategoryZoneDefinition zone = categoryZones.get(zoneIndex);
            if (result.putIfAbsent(zone.id(), zone) != null) {
                throw new V2ValidationException("duplicate category zone id");
            }
            validateCategoryZoneExpansion(zone);
            for (int bindingIndex = 0; bindingIndex < zone.bindings().size(); bindingIndex++) {
                CategoryBindingDefinition binding = zone.bindings().get(bindingIndex);
                if (!bindingIds.add(binding.id())) {
                    throw new V2ValidationException(
                            "duplicate category binding id"
                    );
                }
                try {
                    rules.require(binding.ruleId());
                } catch (IllegalArgumentException exception) {
                    throw new V2ValidationException(
                            "categoryZones[" + zoneIndex + "].bindings[" + bindingIndex
                                    + "].ruleId: " + exception.getMessage(),
                            exception
                    );
                }
                if (binding.wholeStorage()
                        && !ContainerContentsCategoryRule.ID.equals(binding.ruleId())) {
                    throw new V2ValidationException("整片储存区对应仅支持标记箱/木桶识别方式");
                }
                if (binding.markerArea().equals(binding.storageArea())
                        && !UnstackableCategoryRule.ID.equals(binding.ruleId())) {
                    throw new V2ValidationException(
                            "category marker and storage areas must differ"
                    );
                }
                // A dedicated layout runs as its own pass and can reuse legacy storage boxes.
                boolean dedicatedUnstackable = zone.expansion() == null
                        && zone.bindings().size() == 1
                        && UnstackableCategoryRule.ID.equals(binding.ruleId());
                if (!dedicatedUnstackable) {
                    if (storageAreas.stream().anyMatch(area ->
                            areasOverlap(area, binding.storageArea()))) {
                        throw new V2ValidationException("category storage areas must not overlap");
                    }
                    storageAreas.add(binding.storageArea());
                }
                Set<BlockPosition> overridePositions = new HashSet<>();
                for (CategoryContainerOverrideDefinition override
                        : binding.containerOverrides()) {
                    if (!overrideIds.add(override.id())) {
                        throw new V2ValidationException(
                                "duplicate container override id"
                        );
                    }
                    if (!contains(
                            binding.storageArea(),
                            override.storagePosition()
                    )) {
                        throw new V2ValidationException(
                                "container override must be inside storage area"
                        );
                    }
                    if (!overridePositions.add(override.storagePosition())) {
                        throw new V2ValidationException(
                                "duplicate container override position"
                        );
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void validateCategoryZoneExpansion(
            CategoryZoneDefinition zone
    ) {
        if (zone.expansion() == null) {
            return;
        }
        try {
            int templateCount = zone.expansion().templateCount();
            if (templateCount > zone.bindings().size()) {
                throw new V2ValidationException(
                        "单片母版分类物数量超过现有分类范围"
                );
            }
            List<CategoryBindingDefinition> expected = CategoryZoneTiler.expand(
                    zone.expansion(),
                    zone.bindings().subList(0, templateCount)
            );
            if (!expected.equals(zone.bindings())) {
                throw new V2ValidationException(
                        "自动铺满分类区的单片数据与展开设置不一致"
                );
            }
        } catch (V2ValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(exception.getMessage(), exception);
        }
    }

    private static boolean areasOverlap(AreaBounds first, AreaBounds second) {
        return first.minX() <= second.maxX()
                && first.maxX() >= second.minX()
                && first.minY() <= second.maxY()
                && first.maxY() >= second.minY()
                && first.minZ() <= second.maxZ()
                && first.maxZ() >= second.minZ();
    }

    private static boolean contains(
            AreaBounds area,
            BlockPosition position
    ) {
        return position.x() >= area.minX()
                && position.x() <= area.maxX()
                && position.y() >= area.minY()
                && position.y() <= area.maxY()
                && position.z() >= area.minZ()
                && position.z() <= area.maxZ();
    }

    private static void requireUniqueNames(
            Iterable<String> names,
            String resource
    ) {
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            if (!normalized.add(normalizeName(name))) {
                throw new V2ValidationException(
                        resource + " name must be unique: " + name
                );
            }
        }
    }

    public static String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
