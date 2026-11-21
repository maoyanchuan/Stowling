package dev.stowling.v2.model;

import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TaskFlowNormalizer {
    private static final String LEGACY_SORT = ConfigValidator.SORT_CACHE_ITEMS;
    private static final String LEGACY_SORT_ZONE =
            ConfigValidator.SORT_CACHE_ITEMS_BY_CATEGORY_ZONE;
    private static final String LEGACY_COLLECT_REGION =
            ConfigValidator.COLLECT_DROPPED_ITEMS_IN_REGION;
    private static final String LEGACY_REFRESH =
            ConfigValidator.REFRESH_CATEGORY_MARKER_CACHE;
    private static final String LEGACY_AUTO_EAT =
            ConfigValidator.AUTO_EAT_AT_COORDINATE;

    private TaskFlowNormalizer() {
    }

    public static AppConfig normalize(AppConfig config) {
        return normalizeTasks(migrateUnstackableLayouts(config));
    }

    private static AppConfig migrateUnstackableLayouts(AppConfig config) {
        if (config.autoClassifyTasks().stream()
                .noneMatch(definition -> definition.unstackableRegionRef() != null)) {
            return config;
        }
        List<CategoryZoneDefinition> zones = new ArrayList<>(config.categoryZones());
        Set<String> zoneNames = new HashSet<>();
        zones.forEach(zone -> zoneNames.add(ConfigValidator.normalizeName(zone.name())));
        List<AutoClassifyDefinition> definitions = new ArrayList<>();
        Map<UUID, AutoClassifyDefinition> migrated = new LinkedHashMap<>();
        for (AutoClassifyDefinition definition : config.autoClassifyTasks()) {
            String legacyRef = definition.unstackableRegionRef();
            if (legacyRef == null) {
                definitions.add(definition);
                continue;
            }
            UUID regionId = ResourceReference.parse(legacyRef).uuid();
            RegionDefinition region = config.regions().stream()
                    .filter(value -> value.id().equals(regionId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("不可堆叠储存区不存在"));
            List<AutoClassifyAreaDefinition> areas =
                    new ArrayList<>(definition.classificationAreas());
            String zoneRef = unstackableLayoutReference(region, legacyRef, areas, zones, zoneNames);
            if (areas.stream().noneMatch(area -> area.categoryZoneRef().equals(zoneRef))) {
                areas.add(new AutoClassifyAreaDefinition(zoneRef));
            }
            var updated = new AutoClassifyDefinition(definition.id(), definition.name(),
                    definition.enabled(), definition.collectionEnabled(),
                    definition.classificationEnabled(), definition.detectionCoordinateRef(),
                    definition.pickupRadius(), definition.cacheRegionRef(), areas,
                    definition.collectionPriority(), definition.overflowEnabled(),
                    definition.cleanupPolicy(), null, definition.overflowDestinationRef());
            definitions.add(updated);
            migrated.put(updated.classificationTaskId(), updated);
        }
        List<TaskDefinition> tasks = config.tasks().stream().map(task -> {
            AutoClassifyDefinition definition = migrated.get(task.id());
            return definition == null ? task
                    : rebuildAutoClassification(task, definition, "unstackable-layout");
        }).toList();
        return new AppConfig(config.schemaVersion(), config.coordinates(), tasks, config.regions(),
                zones, config.triggers(), config.taskFolders(), definitions);
    }

    private static String unstackableLayoutReference(RegionDefinition region, String regionRef,
            List<AutoClassifyAreaDefinition> areas, List<CategoryZoneDefinition> zones,
            Set<String> zoneNames) {
        var bounds = new AreaBounds(region.firstX(), region.firstY(), region.firstZ(),
                region.secondX(), region.secondY(), region.secondZ());
        Set<String> selected = new HashSet<>();
        areas.forEach(area -> selected.add(area.categoryZoneRef()));
        var existing = zones.stream()
                .filter(zone -> zone.bindings().stream().anyMatch(binding ->
                        UnstackableCategoryRule.ID.equals(binding.ruleId())
                                && bounds.contains(binding.storageArea())
                                && binding.storageArea().contains(bounds)))
                .filter(zone -> selected.contains(layoutReference(zone.id()))
                        || (zone.bindings().size() == 1 && zone.expansion() == null))
                .min(Comparator.comparing(zone -> !selected.contains(layoutReference(zone.id()))));
        if (existing.isPresent()) {
            return layoutReference(existing.orElseThrow().id());
        }
        UUID zoneId = UUID.nameUUIDFromBytes(("unstackable-layout:" + regionRef)
                .getBytes(StandardCharsets.UTF_8));
        if (zones.stream().noneMatch(zone -> zone.id().equals(zoneId))) {
            UUID bindingId = UUID.nameUUIDFromBytes((zoneId + ":binding")
                    .getBytes(StandardCharsets.UTF_8));
            var binding = new CategoryBindingDefinition(bindingId, bounds, bounds,
                    UnstackableCategoryRule.ID);
            zones.add(new CategoryZoneDefinition(zoneId,
                    uniqueName(region.name() + " 不可堆叠", zoneNames), List.of(binding)));
        }
        return layoutReference(zoneId);
    }

    private static String layoutReference(UUID zoneId) {
        return ResourceReference.of(ResourceType.CATEGORY_ZONE, zoneId).encode();
    }

    private static AppConfig normalizeTasks(AppConfig config) {
        Map<UUID, AutoClassifyDefinition> autoCollections = new LinkedHashMap<>();
        Map<UUID, AutoClassifyDefinition> autoClassifications = new LinkedHashMap<>();
        for (AutoClassifyDefinition definition : config.autoClassifyTasks()) {
            autoCollections.put(definition.id(), definition);
            autoClassifications.put(definition.classificationTaskId(), definition);
        }
        Map<UUID, TaskDefinition> originalTasks = new LinkedHashMap<>();
        for (TaskDefinition task : config.tasks()) {
            originalTasks.put(task.id(), task);
        }
        List<TaskDefinition> baseTasks = config.tasks().stream()
                .map(task -> normalizeTask(task, autoCollections.containsKey(task.id())
                        || autoClassifications.containsKey(task.id())))
                .map(task -> upgradeAutoCollection(task, autoCollections.get(task.id())))
                .map(task -> inheritLayoutRecognition(task, autoClassifications.get(task.id())))
                .map(task -> upgradeAutoClassification(task, autoClassifications.get(task.id())))
                .map(task -> autoCleanupPolicy(task, config.autoClassifyTasks()))
                .toList();
        List<TaskDefinition> tasks = new ArrayList<>(baseTasks);
        Set<UUID> taskIds = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (TaskDefinition task : tasks) {
            taskIds.add(task.id());
            names.add(ConfigValidator.normalizeName(task.name()));
        }
        Map<UUID, TaskDefinition> replacements = new LinkedHashMap<>();
        List<TaskDefinition> generated = new ArrayList<>();
        for (TaskDefinition task : baseTasks) {
            TaskDefinition original = originalTasks.get(task.id());
            String legacyRegion = original == null
                    ? null
                    : findLegacyClassificationRegion(original.steps());
            String currentRegion = findClassificationRegion(task.steps());
            String interruptionRef = task.interruptionMode()
                    == TaskInterruptionMode.CUSTOM
                    ? task.interruptionTaskRef()
                    : null;
            UUID interruptionId = generatedInterruptionId(task.id());
            boolean generatedMissing = interruptionRef != null
                    && interruptionRef.equals(
                            ResourceReference.of(
                                    ResourceType.TASK,
                                    interruptionId
                            ).encode()
                    )
                    && !taskIds.contains(interruptionId)
                    && currentRegion != null;
            boolean needsGeneratedFlow = legacyRegion != null
                    && task.interruptionMode() == TaskInterruptionMode.DEFAULT;
            if (!needsGeneratedFlow && !generatedMissing) {
                continue;
            }
            String regionRef = legacyRegion == null
                    ? currentRegion
                    : ensureRegionReference(legacyRegion);
            String reference = ResourceReference.of(
                    ResourceType.TASK,
                    interruptionId
            ).encode();
            TaskDefinition interrupted = new TaskDefinition(
                    task.id(),
                    task.name(),
                    task.priority(),
                    task.repeatWhenIdle(),
                    task.steps(),
                    TaskInterruptionMode.CUSTOM,
                    reference,
                    false,
                    task.requeueAfterInterruption(),
                    task.folderId(),
                    task.cleanupPolicy()
            );
            replacements.put(task.id(), interrupted);
            if (!taskIds.contains(interruptionId)) {
                String generatedName = uniqueName(task.name() + " 收尾", names);
                List<TaskStep> flow = remapStepIds(
                        TaskFlowTemplates.interruption(regionRef),
                        interruptionId
                );
                generated.add(new TaskDefinition(
                        interruptionId,
                        generatedName,
                        TaskPriority.NORMAL,
                        false,
                        flow,
                        TaskInterruptionMode.NONE,
                        null,
                        false,
                        false,
                        task.folderId(),
                        task.cleanupPolicy()
                ));
                taskIds.add(interruptionId);
            }
        }
        if (replacements.isEmpty() && generated.isEmpty()) {
            return new AppConfig(
                    config.schemaVersion(),
                    config.coordinates(),
                    tasks,
                    config.regions(),
                    config.categoryZones(),
                    config.triggers(),
                    config.taskFolders(),
                    config.autoClassifyTasks()
            );
        }
        List<TaskDefinition> replaced = tasks.stream()
                .map(task -> replacements.getOrDefault(task.id(), task))
                .toList();
        List<TaskDefinition> result = new ArrayList<>(replaced);
        result.addAll(generated);
        return new AppConfig(
                config.schemaVersion(),
                config.coordinates(),
                result,
                config.regions(),
                config.categoryZones(),
                config.triggers(),
                config.taskFolders(),
                config.autoClassifyTasks()
        );
    }

    private static TaskDefinition inheritLayoutRecognition(
            TaskDefinition task,
            AutoClassifyDefinition definition
    ) {
        if (definition == null || !hasTaskRecognitionOverrides(task.steps())) {
            return task;
        }
        return rebuildAutoClassification(task, definition, "layout-recognition");
    }

    private static TaskDefinition rebuildAutoClassification(TaskDefinition task,
            AutoClassifyDefinition definition, String role) {
        List<TaskStep> steps = AutoClassifyTaskFactory.tasks(definition, List.of())
                .get(1).steps();
        return new TaskDefinition(task.id(), task.name(), task.priority(), task.repeatWhenIdle(),
                remapStepIds(steps, definition.derivedId(role)),
                task.interruptionMode(), task.interruptionTaskRef(),
                task.defaultInterruptionFlow(), task.requeueAfterInterruption(), task.folderId(),
                task.cleanupPolicy());
    }

    private static boolean hasTaskRecognitionOverrides(List<TaskStep> steps) {
        return steps.stream().anyMatch(step ->
                step.parameters().containsKey(ConfigValidator.CATEGORY_RULE_ID)
                        || step.parameters().containsKey(ConfigValidator.MARKER_SOURCES)
                        || hasTaskRecognitionOverrides(step.steps())
                        || hasTaskRecognitionOverrides(step.otherwise()));
    }

    private static TaskDefinition upgradeAutoClassification(
            TaskDefinition task,
            AutoClassifyDefinition definition
    ) {
        if (definition == null || task.steps().stream().anyMatch(step ->
                ConfigValidator.SYNC_PLAYER_INVENTORY.equals(step.moduleId()))) {
            return task;
        }
        List<TaskStep> steps = new ArrayList<>(task.steps());
        UUID seed = UUID.nameUUIDFromBytes(
                (task.id() + ":global-inventory").getBytes(StandardCharsets.UTF_8)
        );
        if (definition.overflowEnabled() && steps.stream().noneMatch(step ->
                ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId()))) {
            steps.addAll(remapStepIds(List.of(TaskFlowTemplates.overflow(
                    definition.cacheRegionRef(), definition.overflowDestinationRef())),
                    definition.derivedId("overflow")));
        }
        steps.addAll(remapStepIds(TaskFlowTemplates.reconcileInventory(
                definition.cacheRegionRef(), Map.of()
        ), seed));
        return new TaskDefinition(task.id(), task.name(), task.priority(), task.repeatWhenIdle(),
                steps, task.interruptionMode(), task.interruptionTaskRef(),
                task.defaultInterruptionFlow(), task.requeueAfterInterruption(), task.folderId(),
                task.cleanupPolicy());
    }

    private static TaskDefinition autoCleanupPolicy(TaskDefinition task,
            List<AutoClassifyDefinition> definitions) {
        var owner = definitions.stream().filter(definition -> definition.id().equals(task.id())
                || definition.classificationTaskId().equals(task.id())
                || definition.interruptionTaskId().equals(task.id())).findFirst().orElse(null);
        if (owner == null || owner.cleanupPolicy().equals(task.cleanupPolicy())) return task;
        return new TaskDefinition(task.id(), task.name(), task.priority(), task.repeatWhenIdle(),
                task.steps(), task.interruptionMode(), task.interruptionTaskRef(),
                task.defaultInterruptionFlow(), task.requeueAfterInterruption(), task.folderId(),
                owner.cleanupPolicy());
    }

    private static TaskDefinition upgradeAutoCollection(
            TaskDefinition task,
            AutoClassifyDefinition definition
    ) {
        if (definition == null || task.steps().size() < 4) {
            return task;
        }
        List<TaskStep> steps = task.steps();
        if (!ConfigValidator.COLLECT_DROPPED_ITEMS.equals(steps.get(0).moduleId())
                || !ConfigValidator.SCAN_CONTAINERS_IN_REGION.equals(steps.get(1).moduleId())
                || steps.get(2).kind() != TaskStepKind.REPEAT) {
            return task;
        }
        boolean legacyTail = ConfigValidator.ASSERT_NO_MANAGED_ITEMS
                .equals(steps.get(3).moduleId());
        int tailStart = legacyTail ? 4 : 3;
        TaskStep batches = TaskFlowTemplates.collectionBatches(steps.get(0), steps.get(1),
                steps.get(2));
        UUID seed = UUID.nameUUIDFromBytes((task.id() + ":collection-batches")
                .getBytes(StandardCharsets.UTF_8));
        List<TaskStep> upgraded = new ArrayList<>(List.of(steps.getFirst()));
        upgraded.addAll(remapStepIds(List.of(batches), seed));
        if (legacyTail) {
            Map<String, String> parameters = new LinkedHashMap<>(steps.get(0).parameters());
            parameters.putAll(steps.get(1).parameters());
            upgraded.addAll(remapStepIds(TaskFlowTemplates.reconcileInventory(
                    definition.cacheRegionRef(), parameters),
                    steps.get(3).id()));
        }
        upgraded.addAll(steps.subList(tailStart, steps.size()));
        return new TaskDefinition(
                task.id(),
                task.name(),
                task.priority(),
                task.repeatWhenIdle(),
                upgraded,
                task.interruptionMode(),
                task.interruptionTaskRef(),
                task.defaultInterruptionFlow(),
                task.requeueAfterInterruption(),
                task.folderId(),
                task.cleanupPolicy()
        );
    }

    private static TaskDefinition normalizeTask(TaskDefinition task, boolean autoClassify) {
        List<TaskStep> steps = normalizeSteps(task.steps(), autoClassify);
        return new TaskDefinition(
                task.id(),
                task.name(),
                task.priority(),
                task.repeatWhenIdle(),
                steps,
                task.interruptionMode(),
                normalizeTaskReference(task.interruptionTaskRef()),
                task.defaultInterruptionFlow(),
                task.requeueAfterInterruption(),
                task.folderId(),
                task.cleanupPolicy()
        );
    }

    private static List<TaskStep> normalizeSteps(List<TaskStep> steps, boolean autoClassify) {
        List<TaskStep> result = new ArrayList<>();
        for (TaskStep step : steps) {
            result.addAll(normalizeStep(step, autoClassify));
        }
        return List.copyOf(result);
    }

    private static List<TaskStep> normalizeStep(TaskStep step, boolean autoClassify) {
        boolean overflow = ConfigValidator.PACK_OVERFLOW.equals(step.moduleId())
                || ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId());
        if (autoClassify && overflow && !step.parameters().containsKey(ConfigValidator.REGION_REF)) {
            return List.of();
        }
        List<TaskStep> nested = normalizeSteps(step.steps(), autoClassify);
        List<TaskStep> otherwise = normalizeSteps(step.otherwise(), autoClassify);
        Map<String, String> parameters = normalizeParameters(
                step.moduleId(),
                step.kind(),
                step.parameters()
        );
        if (step.kind() != TaskStepKind.ACTION) {
            return List.of(new TaskStep(
                    step.id(),
                    step.kind(),
                    "",
                    parameters,
                    nested,
                    otherwise
            ));
        }
        return switch (step.moduleId()) {
            case LEGACY_AUTO_EAT -> remapStepIds(
                    TaskFlowTemplates.autoEat(
                            parameter(parameters, ConfigValidator.TARGET_REF),
                            parameter(parameters, ConfigValidator.FOOD_ITEM_ID),
                            parameters.get(ConfigValidator.FOOD_LEVEL_THRESHOLD)
                    ),
                    step.id()
            );
            case LEGACY_SORT_ZONE -> remapStepIds(
                    TaskFlowTemplates.sortByCategoryZone(
                            parameter(parameters, ConfigValidator.REGION_REF),
                            parameter(
                                    parameters,
                                    ConfigValidator.CATEGORY_ZONE_REF
                            ),
                            scanParameters(parameters)
                    ),
                    step.id()
            );
            case LEGACY_SORT -> normalizeLegacySort(step, parameters);
            case LEGACY_REFRESH -> remapStepIds(
                    TaskFlowTemplates.refreshMarkers(
                            parameter(
                                    parameters,
                                    ConfigValidator.CATEGORY_ZONE_REF
                            ),
                            parameters.get(ConfigValidator.MAX_VOLUME)
                    ),
                    step.id()
            );
            case LEGACY_COLLECT_REGION -> normalizeLegacyRegionCollect(
                    step,
                    parameters
            );
            case ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS ->
                    normalizeDeposit(step, parameters, nested, otherwise);
            case ConfigValidator.SYNC_COLLECTED_INVENTORY -> List.of(TaskStep.action(
                    step.id(), ConfigValidator.SYNC_PLAYER_INVENTORY, parameters
            ));
            default -> List.of(new TaskStep(
                    step.id(),
                    TaskStepKind.ACTION,
                    autoClassify && overflow
                            ? ConfigValidator.ROBOT_WAREHOUSE : step.moduleId(),
                    parameters,
                    nested,
                    otherwise
            ));
        };
    }

    private static List<TaskStep> normalizeLegacyRegionCollect(
            TaskStep step,
            Map<String, String> parameters
    ) {
        String region = parameter(parameters, ConfigValidator.REGION_REF);
        Map<String, String> collect = new LinkedHashMap<>();
        copyIfPresent(parameters, collect, ConfigValidator.ITEM_FILTER);
        copyIfPresent(parameters, collect, ConfigValidator.TIMEOUT_SECONDS);
        copyIfPresent(parameters, collect, ConfigValidator.CONTAINER_TYPES);
        copyIfPresent(parameters, collect, ConfigValidator.MAX_VOLUME);
        return remapStepIds(
                TaskFlowTemplates.collect(region, region, collect),
                step.id()
        );
    }

    private static List<TaskStep> normalizeLegacySort(
            TaskStep step,
            Map<String, String> parameters
    ) {
        String zone = parameters.get(ConfigValidator.CATEGORY_ZONE_REF);
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException(
                    "SORT_CACHE_ITEMS requires categoryZoneRef"
            );
        }
        return remapStepIds(
                TaskFlowTemplates.sortByCategoryZone(
                        parameter(parameters, ConfigValidator.REGION_REF),
                        zone,
                        scanParameters(parameters)
                ),
                step.id()
        );
    }

    private static List<TaskStep> normalizeDeposit(
            TaskStep step,
            Map<String, String> parameters,
            List<TaskStep> nested,
            List<TaskStep> otherwise
    ) {
        Map<String, String> normalized = new LinkedHashMap<>(parameters);
        String regionRef = normalized.remove(ConfigValidator.REGION_REF);
        boolean legacySource = "collected-this-run".equals(
                normalized.get(ConfigValidator.ITEM_SOURCE)
        );
        boolean hadScanSource =
                normalized.remove(ConfigValidator.SCAN_SOURCE) != null;
        boolean hadFillMode =
                normalized.remove(ConfigValidator.FILL_MODE) != null;
        boolean legacyControl = hadScanSource || hadFillMode || legacySource;
        String targetRef = normalized.get(ConfigValidator.TARGET_REF);
        if (targetRef != null && !targetRef.isBlank()) {
            normalizeDepositSource(normalized);
            return List.of(new TaskStep(
                    step.id(),
                    TaskStepKind.ACTION,
                    ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                    normalized,
                    List.of(),
                    List.of()
            ));
        }
        boolean priorScan = legacyControl
                || normalized.isEmpty()
                || regionRef != null;
        if (!priorScan) {
            normalized.put(
                    ConfigValidator.TARGET_REF,
                    ResourceReference.output("container").encode()
            );
            normalizeDepositSource(normalized);
            return List.of(new TaskStep(
                    step.id(),
                    TaskStepKind.ACTION,
                    ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                    normalized,
                    List.of(),
                    List.of()
            ));
        }
        return remapStepIds(
                TaskFlowTemplates.depositCollected(
                        regionRef,
                        regionRef != null
                ),
                step.id()
        );
    }

    private static void normalizeDepositSource(Map<String, String> parameters) {
        String source = parameters.get(ConfigValidator.ITEM_SOURCE);
        if ("collected-this-run".equals(source)) {
            parameters.put(ConfigValidator.ITEM_SOURCE, "collected");
        }
    }

    private static Map<String, String> normalizeParameters(
            String moduleId,
            TaskStepKind kind,
            Map<String, String> original
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : original.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (isTypedKey(key)) {
                result.put(key, normalizeTypedValue(key, value));
            } else {
                result.put(key, value);
            }
        }
        if (kind == TaskStepKind.CREATE_TASK) {
            moveAlias(result, ConfigValidator.TASK_REF, ResourceType.TASK);
            normalizeReference(result, ConfigValidator.TASK_REF, ResourceType.TASK);
            return Map.copyOf(result);
        }
        switch (moduleId) {
            case ConfigValidator.MOVE_TO_COORDINATE,
                    ConfigValidator.WITHDRAW_FOOD -> {
                moveAlias(
                        result,
                        ConfigValidator.COORDINATE_ID,
                        ConfigValidator.TARGET_REF,
                        ResourceType.COORDINATE
                );
                normalizeReference(
                        result,
                        ConfigValidator.TARGET_REF,
                        ResourceType.COORDINATE
                );
            }
            case ConfigValidator.COLLECT_DROPPED_ITEMS -> {
                moveAreaAlias(result, ConfigValidator.AREA_REFERENCE);
                moveAreaAlias(result, ConfigValidator.COORDINATE_ID);
                moveAreaAlias(result, ConfigValidator.REGION_ID);
                normalizeArea(result, ConfigValidator.AREA_REF);
            }
            case ConfigValidator.SCAN_CONTAINERS_IN_REGION -> {
                moveAlias(
                        result,
                        ConfigValidator.REGION_ID,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                moveAlias(
                        result,
                        ConfigValidator.CACHE_REGION_ID,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                normalizeReference(
                        result,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
            }
            case ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                    ConfigValidator.SCAN_CATEGORY_MARKERS,
                    ConfigValidator.SAVE_CATEGORY_MARKER_CACHE -> {
                moveAlias(
                        result,
                        ConfigValidator.CATEGORY_ZONE_ID,
                        ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE
                );
                normalizeReference(
                        result,
                        ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE
                );
            }
            case LEGACY_SORT, LEGACY_SORT_ZONE -> {
                moveAlias(
                        result,
                        ConfigValidator.CACHE_REGION_ID,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                moveAlias(
                        result,
                        ConfigValidator.CATEGORY_ZONE_ID,
                        ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE
                );
                normalizeReference(
                        result,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                normalizeReference(
                        result,
                        ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE
                );
            }
            case LEGACY_COLLECT_REGION -> {
                moveAlias(
                        result,
                        ConfigValidator.REGION_ID,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                normalizeReference(
                        result,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
            }
            case LEGACY_REFRESH -> {
                moveAlias(
                        result,
                        ConfigValidator.CATEGORY_ZONE_ID,
                        ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE
                );
                normalizeReference(
                        result,
                        ConfigValidator.CATEGORY_ZONE_REF,
                        ResourceType.CATEGORY_ZONE
                );
            }
            case LEGACY_AUTO_EAT -> {
                moveAlias(
                        result,
                        ConfigValidator.COORDINATE_ID,
                        ConfigValidator.TARGET_REF,
                        ResourceType.COORDINATE
                );
                normalizeReference(
                        result,
                        ConfigValidator.TARGET_REF,
                        ResourceType.COORDINATE
                );
            }
            case ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS -> {
                moveAlias(
                        result,
                        ConfigValidator.REGION_ID,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                moveAlias(
                        result,
                        ConfigValidator.CACHE_REGION_ID,
                        ConfigValidator.REGION_REF,
                        ResourceType.REGION
                );
                normalizeAnyReference(result, ConfigValidator.TARGET_REF);
            }
            case ConfigValidator.WITHDRAW_ITEMS ->
                    normalizeAnyReference(result, ConfigValidator.TARGET_REF);
            case ConfigValidator.READ_CONTAINER -> normalizeAnyReference(
                    result,
                    ConfigValidator.TARGET_REF
            );
            default -> {
            }
        }
        return Map.copyOf(result);
    }

    private static void moveAreaAlias(
            Map<String, String> parameters,
            String alias
    ) {
        String value = parameters.remove(alias);
        if (value == null || value.isBlank()) {
            return;
        }
        ResourceReference reference = parseReference(
                value,
                ResourceType.COORDINATE
        );
        if (reference.type() != ResourceType.COORDINATE
                && reference.type() != ResourceType.REGION) {
            throw new IllegalArgumentException("areaRef must be coordinate or region");
        }
        parameters.put(ConfigValidator.AREA_REF, reference.encode());
    }

    private static void moveAlias(
            Map<String, String> parameters,
            String alias,
            String target,
            ResourceType type
    ) {
        String value = parameters.remove(alias);
        if (value != null && !value.isBlank()) {
            parameters.put(target, parseReference(value, type).encode());
        }
    }

    private static void moveAlias(
            Map<String, String> parameters,
            String target,
            ResourceType type
    ) {
        moveAlias(parameters, target, target, type);
    }

    private static void normalizeReference(
            Map<String, String> parameters,
            String key,
            ResourceType type
    ) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        parameters.put(key, parseReference(value, type).encode());
    }

    private static void normalizeAnyReference(
            Map<String, String> parameters,
            String key
    ) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        parameters.put(key, ResourceReference.parse(value).encode());
    }

    private static void normalizeArea(
            Map<String, String> parameters,
            String key
    ) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            return;
        }
        ResourceReference reference = parseReference(value, ResourceType.COORDINATE);
        if (reference.type() != ResourceType.COORDINATE
                && reference.type() != ResourceType.REGION) {
            throw new IllegalArgumentException("areaRef must be coordinate or region");
        }
        parameters.put(key, reference.encode());
    }

    private static String normalizeTypedValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        ResourceType type = switch (key) {
            case ConfigValidator.REGION_REF -> ResourceType.REGION;
            case ConfigValidator.CATEGORY_ZONE_REF -> ResourceType.CATEGORY_ZONE;
            case ConfigValidator.TASK_REF -> ResourceType.TASK;
            case ConfigValidator.AREA_REF, ConfigValidator.TARGET_REF -> null;
            default -> null;
        };
        if (type == null) {
            return value;
        }
        return parseReference(value, type).encode();
    }

    private static boolean isTypedKey(String key) {
        return ConfigValidator.TARGET_REF.equals(key)
                || ConfigValidator.AREA_REF.equals(key)
                || ConfigValidator.REGION_REF.equals(key)
                || ConfigValidator.CATEGORY_ZONE_REF.equals(key)
                || ConfigValidator.TASK_REF.equals(key);
    }

    private static ResourceReference parseReference(
            String value,
            ResourceType fallback
    ) {
        try {
            if (value.contains(":")) {
                return ResourceReference.parse(value);
            }
            return new ResourceReference(fallback, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "invalid resource reference: " + value,
                    exception
            );
        }
    }

    private static String normalizeTaskReference(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseReference(value, ResourceType.TASK).encode();
    }

    private static Map<String, String> scanParameters(
            Map<String, String> parameters
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        copyIfPresent(parameters, result, ConfigValidator.CONTAINER_TYPES);
        copyIfPresent(parameters, result, ConfigValidator.MAX_VOLUME);
        copyIfPresent(parameters, result, ConfigValidator.MARKER_SOURCES);
        return Map.copyOf(result);
    }

    private static String parameter(
            Map<String, String> parameters,
            String key
    ) {
        String value = parameters.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
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

    private static String findLegacyClassificationRegion(List<TaskStep> steps) {
        for (TaskStep step : steps) {
            if (ConfigValidator.SORT_CACHE_ITEMS.equals(step.moduleId())
                    || ConfigValidator.SORT_CACHE_ITEMS_BY_CATEGORY_ZONE.equals(
                            step.moduleId()
                    )) {
                String value = step.parameters().get(ConfigValidator.CACHE_REGION_ID);
                if (value == null) {
                    value = step.parameters().get(ConfigValidator.REGION_REF);
                }
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            String nested = findLegacyClassificationRegion(step.steps());
            if (nested != null) {
                return nested;
            }
            String alternative = findLegacyClassificationRegion(step.otherwise());
            if (alternative != null) {
                return alternative;
            }
        }
        return null;
    }

    private static String findClassificationRegion(List<TaskStep> steps) {
        String lastScan = null;
        for (TaskStep step : steps) {
            if (ConfigValidator.SCAN_CONTAINERS_IN_REGION.equals(
                    step.moduleId()
            )) {
                String value = step.parameters().get(ConfigValidator.REGION_REF);
                if (value != null && !value.isBlank()) {
                    lastScan = value;
                }
            }
            if (containsCondition(step.steps(), TaskCondition.CACHE_HAS_MORE)
                    || containsCondition(
                            step.otherwise(),
                            TaskCondition.CACHE_HAS_MORE
                    )) {
                if (lastScan != null) {
                    return lastScan;
                }
            }
            String nested = findClassificationRegion(step.steps());
            if (nested != null) {
                return nested;
            }
            String alternative = findClassificationRegion(step.otherwise());
            if (alternative != null) {
                return alternative;
            }
        }
        return lastScan;
    }

    private static boolean containsCondition(
            List<TaskStep> steps,
            TaskCondition condition
    ) {
        for (TaskStep step : steps) {
            if (step.kind() == TaskStepKind.REPEAT
                    && condition.name().equals(
                            step.parameters().get(TaskFlowTemplates.CONDITION)
                    )) {
                return true;
            }
            if (containsCondition(step.steps(), condition)
                    || containsCondition(step.otherwise(), condition)) {
                return true;
            }
        }
        return false;
    }

    private static String ensureRegionReference(String value) {
        return value.contains(":")
                ? ResourceReference.parse(value).encode()
                : ResourceReference.of(
                        ResourceType.REGION,
                        UUID.fromString(value)
                ).encode();
    }

    private static UUID generatedInterruptionId(UUID taskId) {
        return UUID.nameUUIDFromBytes(
                (taskId + ":interruption").getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String uniqueName(String candidate, Set<String> names) {
        String base = candidate.length() > 80
                ? candidate.substring(0, 80)
                : candidate;
        String result = base;
        int suffix = 2;
        while (names.contains(ConfigValidator.normalizeName(result))) {
            String marker = " " + suffix++;
            int prefixLength = Math.min(base.length(), 80 - marker.length());
            result = base.substring(0, prefixLength) + marker;
        }
        names.add(ConfigValidator.normalizeName(result));
        return result;
    }

    private static List<TaskStep> remapStepIds(
            List<TaskStep> steps,
            UUID seed
    ) {
        int[] index = {0};
        return remapStepIds(steps, seed, index);
    }

    private static List<TaskStep> remapStepIds(
            List<TaskStep> steps,
            UUID seed,
            int[] index
    ) {
        List<TaskStep> result = new ArrayList<>();
        for (TaskStep step : steps) {
            UUID id = UUID.nameUUIDFromBytes(
                    (seed + ":" + index[0]++)
                            .getBytes(StandardCharsets.UTF_8)
            );
            result.add(new TaskStep(
                    id,
                    step.kind(),
                    step.moduleId(),
                    step.parameters(),
                    remapStepIds(step.steps(), seed, index),
                    remapStepIds(step.otherwise(), seed, index)
            ));
        }
        return List.copyOf(result);
    }

    public static boolean references(
            TaskDefinition task,
            ResourceReference reference
    ) {
        if (reference.encode().equals(task.cleanupPolicy().destinationRef())) return true;
        if (reference.type() == ResourceType.TASK
                && reference.encode().equals(task.interruptionTaskRef())) {
            return true;
        }
        return references(task.steps(), reference);
    }

    private static boolean references(
            List<TaskStep> steps,
            ResourceReference reference
    ) {
        for (TaskStep step : steps) {
            if (stepReferences(step, reference)
                    || references(step.steps(), reference)
                    || references(step.otherwise(), reference)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stepReferences(
            TaskStep step,
            ResourceReference reference
    ) {
        for (Map.Entry<String, String> entry : step.parameters().entrySet()) {
            if (parameterReferences(entry.getKey(), entry.getValue(), reference)) {
                return true;
            }
        }
        return false;
    }

    private static boolean parameterReferences(
            String key,
            String value,
            ResourceReference reference
    ) {
        try {
            return switch (key) {
                case ConfigValidator.TARGET_REF, ConfigValidator.AREA_REF,
                        ConfigValidator.AREA_REFERENCE ->
                        ResourceReference.parse(value).equals(reference);
                case ConfigValidator.REGION_REF, ConfigValidator.REGION_ID,
                        ConfigValidator.CACHE_REGION_ID ->
                        reference.type() == ResourceType.REGION
                                && parseReference(value, ResourceType.REGION)
                                .equals(reference);
                case ConfigValidator.CATEGORY_ZONE_REF,
                        ConfigValidator.CATEGORY_ZONE_ID ->
                        reference.type() == ResourceType.CATEGORY_ZONE
                                && parseReference(
                                        value,
                                        ResourceType.CATEGORY_ZONE
                                ).equals(reference);
                case ConfigValidator.TASK_REF ->
                        reference.type() == ResourceType.TASK
                                && parseReference(value, ResourceType.TASK)
                                .equals(reference);
                case ConfigValidator.COORDINATE_ID ->
                        reference.type() == ResourceType.COORDINATE
                                && parseReference(
                                        value,
                                        ResourceType.COORDINATE
                                ).equals(reference);
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
