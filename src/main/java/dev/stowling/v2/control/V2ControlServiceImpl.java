package dev.stowling.v2.control;

import dev.stowling.control.InventoryInfo;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2ConfigSnapshot;
import dev.stowling.v2.config.V2RevisionConflictException;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneExpansionAxis;
import dev.stowling.v2.model.CategoryZoneExpansionDefinition;
import dev.stowling.v2.model.CategoryZoneTiler;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskFolderDefinition;
import dev.stowling.v2.model.TaskFlowNormalizer;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.module.TaskModuleCatalog;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.TaskRunSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class V2ControlServiceImpl implements V2ControlService {
    private dev.stowling.v2.config.StorageSettingsStore storageSettings;
    private final ConfigStore configs;
    private final TaskRunManager runs;
    private final Supplier<List<InventoryInfo>> inventory;
    private final String configurationNotice;
    private Supplier<dev.stowling.inventory.WarehouseInventory> warehouse;
    private final CategoryRuleRegistry categoryRules =
            CategoryRuleRegistry.standard();

    public V2ControlServiceImpl(
            ConfigStore configs,
            TaskRunManager runs,
            Supplier<List<InventoryInfo>> inventory
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        AppConfig config = configs.read().config();
        this.configurationNotice = usesUnstackableCompatibility(config)
                ? "已载入旧版不可堆叠分类设置，可以继续使用已有的储存范围和任务。"
                : null;
        runs.refreshTriggers(config);
    }

    private static boolean usesUnstackableCompatibility(AppConfig config) {
        return config.categoryZones().stream().flatMap(zone -> zone.bindings().stream())
                .anyMatch(binding -> UnstackableCategoryRule.ID.equals(binding.ruleId()))
                || config.tasks().stream().anyMatch(task -> hasUnstackableStep(task.steps()));
    }

    private static boolean hasUnstackableStep(List<TaskStep> steps) {
        return steps.stream().anyMatch(step ->
                ConfigValidator.SORT_UNSTACKABLE_ITEMS.equals(step.moduleId())
                        || hasUnstackableStep(step.steps())
                        || hasUnstackableStep(step.otherwise()));
    }

    public V2ControlServiceImpl(ConfigStore configs, TaskRunManager runs,
            Supplier<List<InventoryInfo>> inventory,
            dev.stowling.v2.config.StorageSettingsStore storageSettings) {
        this(configs, runs, inventory);
        this.storageSettings = storageSettings;
    }

    public V2ControlServiceImpl(ConfigStore configs, TaskRunManager runs,
            Supplier<List<InventoryInfo>> inventory,
            dev.stowling.v2.config.StorageSettingsStore settings,
            Supplier<dev.stowling.inventory.WarehouseInventory> warehouse) {
        this(configs, runs, inventory, settings);
        this.warehouse = Objects.requireNonNull(warehouse);
    }

    @Override
    public dev.stowling.inventory.WarehouseInventory warehouseInventory() {
        return warehouse == null ? V2ControlService.super.warehouseInventory() : warehouse.get();
    }

    @Override
    public dev.stowling.v2.config.StorageSettingsStore.Snapshot storageSettings() {
        return storageSettings.read();
    }

    @Override
    public synchronized dev.stowling.v2.config.StorageSettingsStore.Snapshot saveStorageSettings(
            String revision, dev.stowling.v2.model.StorageAutomationSettings settings) {
        if (runs.busy() && runs.status().map(value -> value.state()
                != dev.stowling.v2.runtime.TaskRunState.INTERRUPTION_FAILED).orElse(false)) {
            throw new IllegalArgumentException("请等待当前任务结束后修改全局储存配置");
        }
        var points = configs.read().config().coordinates();
        Set<UUID> ids = points.stream().map(CoordinatePoint::id)
                .collect(java.util.stream.Collectors.toSet());
        List<UUID> referenced = new ArrayList<>(settings.temporaryCoordinateIds());
        referenced.addAll(settings.emptyBoxCoordinateIds());
        referenced.addAll(settings.overflowCoordinateIds());
        if (settings.fallbackCoordinateId() != null) {
            referenced.add(settings.fallbackCoordinateId());
        }
        if (!ids.containsAll(referenced)) {
            throw new IllegalArgumentException("公共设施引用的坐标不存在，请刷新后重新选择");
        }
        Set<UUID> regions = configs.read().config().regions().stream()
                .map(RegionDefinition::id).collect(java.util.stream.Collectors.toSet());
        if (!regions.containsAll(settings.temporaryRegionIds())
                || !regions.containsAll(settings.overflowRegionIds())) {
            throw new IllegalArgumentException("公共设施引用的区域不存在，请刷新后重新选择");
        }
        if (!settings.temporaryContainers().isEmpty() || !settings.emptyBoxContainers().isEmpty()
                || settings.fallbackPackingPosition() != null) {
            throw new IllegalArgumentException("请在坐标页创建位置，再选择公共设施引用");
        }
        return storageSettings.save(revision, settings);
    }

    @Override
    public synchronized StatusResponse status() {
        String state = runs.status()
                .map(run -> run.state().name())
                .orElse("IDLE");
        return new StatusResponse(state, Instant.now(), configurationNotice);
    }

    @Override
    public List<InventoryInfo> inventory() {
        return List.copyOf(inventory.get());
    }

    @Override
    public PlayerInventoryResponse playerInventory() {
        return runs.playerInventory();
    }

    @Override
    public PlayerInventoryResponse allocatePlayerInventory(String revision, String key,
            int keepCount) {
        return runs.allocatePlayerInventory(revision, key, keepCount);
    }

    @Override
    public ModuleListResponse modules() {
        return new ModuleListResponse(TaskModuleCatalog.modules());
    }

    @Override
    public synchronized CoordinateListResponse coordinates() {
        return coordinateResponse(configs.read());
    }

    @Override
    public synchronized CoordinatePoint coordinate(UUID id) {
        return requireCoordinate(configs.read().config(), id);
    }

    @Override
    public synchronized CoordinateListResponse createCoordinate(
            CoordinateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        List<CoordinatePoint> coordinates =
                new ArrayList<>(current.config().coordinates());
        coordinates.add(new CoordinatePoint(
                UUID.randomUUID(),
                request.name(),
                request.x(),
                request.y(),
                request.z(),
                request.dimension(),
                request.radius()
        ));
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return coordinateResponse(saved);
    }

    @Override
    public synchronized CoordinateListResponse updateCoordinate(
            UUID id,
            CoordinateUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        CoordinatePoint existing = requireCoordinate(current.config(), id);
        CoordinatePoint replacement = new CoordinatePoint(
                existing.id(),
                request.name(),
                request.x(),
                request.y(),
                request.z(),
                request.dimension(),
                request.radius()
        );
        List<CoordinatePoint> coordinates = current.config().coordinates().stream()
                .map(point -> point.id().equals(id) ? replacement : point)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return coordinateResponse(saved);
    }

    @Override
    public synchronized CoordinateListResponse deleteCoordinate(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireCoordinate(current.config(), id);
        String coordinateId = id.toString();
        List<String> references = new ArrayList<>();
        references.addAll(partialBoxReferences(current.config(), "coordinate:" + id));
        ResourceReference reference = ResourceReference.of(
                ResourceType.COORDINATE,
                id
        );
        references.addAll(current.config().tasks().stream()
                .filter(task -> TaskFlowNormalizer.references(task, reference))
                .map(TaskDefinition::name)
                .toList());
        references.addAll(current.config().triggers().stream()
                .filter(trigger -> coordinateId.equals(
                        trigger.coordinateId() == null
                                ? null
                                : trigger.coordinateId().toString()
                ))
                .map(TaskTriggerDefinition::name)
                .toList());
        if (storageSettings != null) {
            var facilities = storageSettings.read().settings();
            if (facilities.temporaryCoordinateIds().contains(id)) references.add("公共设施·暂存箱");
            if (facilities.emptyBoxCoordinateIds().contains(id)) references.add("公共设施·空盒供应");
            if (facilities.overflowCoordinateIds().contains(id)) references.add("公共设施·溢出箱");
            if (id.equals(facilities.fallbackCoordinateId())) references.add("公共设施·后备工位");
        }
        if (!references.isEmpty()) {
            throw new CoordinateInUseException(references);
        }
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates().stream()
                        .filter(point -> !point.id().equals(id))
                        .toList(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return coordinateResponse(saved);
    }

    @Override
    public synchronized RegionListResponse regions() {
        return regionResponse(configs.read());
    }

    @Override
    public synchronized RegionDefinition region(UUID id) {
        return requireRegion(configs.read().config(), id);
    }

    @Override
    public synchronized RegionListResponse createRegion(RegionRequest request) {
        V2ConfigSnapshot current = configs.read();
        List<RegionDefinition> regions =
                new ArrayList<>(current.config().regions());
        regions.add(new RegionDefinition(
                UUID.randomUUID(),
                request.name(),
                request.firstX(),
                request.firstY(),
                request.firstZ(),
                request.secondX(),
                request.secondY(),
                request.secondZ()
        ));
        return regionResponse(configs.save(current.revision(), new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                regions,
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        )));
    }

    @Override
    public synchronized RegionListResponse updateRegion(
            UUID id,
            RegionUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireRegion(current.config(), id);
        List<RegionDefinition> regions = current.config().regions().stream()
                .map(region -> region.id().equals(id)
                        ? new RegionDefinition(
                                id,
                                request.name(),
                                request.firstX(),
                                request.firstY(),
                                request.firstZ(),
                                request.secondX(),
                                request.secondY(),
                                request.secondZ()
                        )
                        : region)
                .toList();
        return regionResponse(configs.save(current.revision(), new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                regions,
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        )));
    }

    @Override
    public synchronized RegionListResponse deleteRegion(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireRegion(current.config(), id);
        ResourceReference reference = ResourceReference.of(
                ResourceType.REGION,
                id
        );
        List<String> references = new ArrayList<>(
                current.config().tasks().stream()
                        .filter(task -> TaskFlowNormalizer.references(
                                task,
                                reference
                        ))
                        .map(TaskDefinition::name)
                        .toList()
        );
        references.addAll(partialBoxReferences(current.config(), "region:" + id));
        references.addAll(current.config().triggers().stream()
                .filter(trigger -> id.equals(trigger.cacheRegionId()))
                .map(TaskTriggerDefinition::name)
                .toList());
        if (storageSettings != null) {
            var facilities = storageSettings.read().settings();
            if (facilities.temporaryRegionIds().contains(id)) references.add("公共设施·暂存区域");
            if (facilities.overflowRegionIds().contains(id)) references.add("公共设施·溢出区域");
        }
        if (!references.isEmpty()) {
            throw new V2ValidationException(
                    "region is used by: " + String.join(", ", references)
            );
        }
        return regionResponse(configs.save(current.revision(), new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions().stream()
                        .filter(region -> !region.id().equals(id))
                        .toList(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        )));
    }

    @Override
    public CategoryRuleListResponse categoryRules() {
        return new CategoryRuleListResponse(categoryRules.definitions());
    }

    private static List<String> partialBoxReferences(AppConfig config, String reference) {
        List<String> names = new ArrayList<>();
        for (var task : config.autoClassifyTasks()) {
            if (task.classificationAreas().stream().anyMatch(area -> area.storagePolicy() != null
                    && reference.equals(area.storagePolicy().partialBoxDestinationRef()))) {
                names.add(task.name() + "·未满盒储存");
            }
        }
        for (var zone : config.categoryZones()) {
            if (zone.bindings().stream().anyMatch(binding -> reference.equals(
                    binding.storagePolicy().partialBoxDestinationRef()))) {
                names.add(zone.name() + "·未满盒储存");
            }
        }
        return names;
    }

    @Override
    public synchronized CategoryZoneListResponse categoryZones() {
        return categoryZoneResponse(configs.read());
    }

    @Override
    public synchronized CategoryZoneDefinition categoryZone(UUID id) {
        return requireCategoryZone(configs.read().config(), id);
    }

    @Override
    public synchronized CategoryZoneListResponse createCategoryZone(
            CategoryZoneRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        List<CategoryZoneDefinition> zones = new ArrayList<>(
                current.config().categoryZones()
        );
        zones.add(categoryZone(
                UUID.randomUUID(),
                request.name(),
                request.bindings(),
                request.expansion(),
                null
        ));
        return categoryZoneResponse(configs.save(
                current.revision(),
                new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        current.config().coordinates(),
                        current.config().tasks(),
                        current.config().regions(),
                        zones,
                        current.config().triggers(),
                        current.config().taskFolders(),
                        current.config().autoClassifyTasks()
                )
        ));
    }

    @Override
    public synchronized CategoryZoneListResponse updateCategoryZone(
            UUID id,
            CategoryZoneUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        CategoryZoneDefinition existing = requireCategoryZone(
                current.config(),
                id
        );
        CategoryZoneDefinition replacement = categoryZone(
                existing.id(),
                request.name(),
                request.bindings(),
                request.expansion(),
                existing
        );
        List<CategoryZoneDefinition> zones = current.config()
                .categoryZones()
                .stream()
                .map(zone -> zone.id().equals(id) ? replacement : zone)
                .toList();
        return categoryZoneResponse(configs.save(
                current.revision(),
                new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        current.config().coordinates(),
                        current.config().tasks(),
                        current.config().regions(),
                        zones,
                        current.config().triggers(),
                        current.config().taskFolders(),
                        current.config().autoClassifyTasks()
                )
        ));
    }

    @Override
    public synchronized CategoryZoneListResponse deleteCategoryZone(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireCategoryZone(current.config(), id);
        ResourceReference reference = ResourceReference.of(
                ResourceType.CATEGORY_ZONE,
                id
        );
        List<String> references = current.config().tasks().stream()
                .filter(task -> TaskFlowNormalizer.references(task, reference))
                .map(TaskDefinition::name)
                .toList();
        if (!references.isEmpty()) {
            throw new V2ValidationException(
                    "category zone is used by tasks: "
                            + String.join(", ", references)
            );
        }
        return categoryZoneResponse(configs.save(
                current.revision(),
                new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        current.config().coordinates(),
                        current.config().tasks(),
                        current.config().regions(),
                        current.config().categoryZones().stream()
                                .filter(zone -> !zone.id().equals(id))
                                .toList(),
                        current.config().triggers(),
                        current.config().taskFolders(),
                        current.config().autoClassifyTasks()
                )
        ));
    }

    @Override
    public synchronized TaskFolderListResponse taskFolders() {
        return taskFolderResponse(configs.read());
    }

    @Override
    public synchronized TaskFolderListResponse createTaskFolder(
            TaskFolderRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        List<TaskFolderDefinition> folders = new ArrayList<>(
                current.config().taskFolders()
        );
        folders.add(new TaskFolderDefinition(
                UUID.randomUUID(),
                request.name()
        ));
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                folders,
                current.config().autoClassifyTasks()
        );
        return taskFolderResponse(configs.save(current.revision(), updated));
    }

    @Override
    public synchronized TaskFolderListResponse updateTaskFolder(
            UUID id,
            TaskFolderUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireTaskFolder(current.config(), id);
        TaskFolderDefinition replacement = new TaskFolderDefinition(
                id,
                request.name()
        );
        List<TaskFolderDefinition> folders = current.config().taskFolders()
                .stream()
                .map(folder -> folder.id().equals(id) ? replacement : folder)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                folders,
                current.config().autoClassifyTasks()
        );
        return taskFolderResponse(configs.save(current.revision(), updated));
    }

    @Override
    public synchronized TaskFolderListResponse deleteTaskFolder(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireTaskFolder(current.config(), id);
        List<TaskDefinition> tasks = current.config().tasks().stream()
                .map(task -> id.equals(task.folderId())
                        ? withFolder(task, null)
                        : task)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks,
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders().stream()
                        .filter(folder -> !folder.id().equals(id))
                        .toList(),
                current.config().autoClassifyTasks()
        );
        return taskFolderResponse(configs.save(current.revision(), updated));
    }

    @Override
    public synchronized TaskListResponse tasks() {
        return taskResponse(configs.read());
    }

    @Override
    public synchronized AutoClassifyTaskListResponse autoClassifyTasks() {
        V2ConfigSnapshot snapshot = configs.read();
        List<AutoClassifyTask> items = snapshot.config().autoClassifyTasks()
                .stream()
                .map(definition -> autoView(definition, snapshot.config()))
                .toList();
        return new AutoClassifyTaskListResponse(snapshot.revision(), items);
    }

    @Override
    public synchronized AutoClassifyTaskResponse createAutoClassifyTask(
            AutoClassifyTaskRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        String name = uniqueAutoName(
                request.name(),
                current.config(),
                null
        );
        AutoClassifyDefinition definition = definition(
                UUID.randomUUID(),
                name,
                request.enabled(),
                request.collectionEnabled() == null || request.collectionEnabled(),
                request.classificationEnabled() == null || request.classificationEnabled(),
                request.detection(),
                request.cacheRegionRef(),
                request.classificationAreas(),
                request.collectionPriority(),
                Boolean.TRUE.equals(request.overflowEnabled()),
                request.cleanupPolicy()
        );
        List<TaskDefinition> tasks = new ArrayList<>(current.config().tasks());
        tasks.addAll(AutoClassifyTaskFactory.tasks(
                definition,
                tasks.stream().map(TaskDefinition::name).toList()
        ));
        List<TaskTriggerDefinition> triggers = new ArrayList<>(
                current.config().triggers()
        );
        triggers.addAll(AutoClassifyTaskFactory.triggers(
                definition,
                triggers.stream().map(TaskTriggerDefinition::name).toList()
        ));
        List<AutoClassifyDefinition> definitions = new ArrayList<>(
                current.config().autoClassifyTasks()
        );
        definitions.add(definition);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks,
                current.config().regions(),
                current.config().categoryZones(),
                triggers,
                current.config().taskFolders(),
                definitions
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return new AutoClassifyTaskResponse(
                saved.revision(),
                autoView(definition, saved.config())
        );
    }

    @Override
    public synchronized AutoClassifyTaskResponse updateAutoClassifyTask(
            UUID id,
            AutoClassifyTaskUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        AutoClassifyDefinition existing = requireAutoClassify(
                current.config(),
                id
        );
        Set<UUID> pendingIds = AutoClassifyTaskFactory.ownedTaskIds(
                List.of(existing)
        );
        for (UUID pendingId : pendingIds) {
            if (runs.hasPendingTask(pendingId)) {
                throw new IllegalStateException(
                        "自动分类仍有活动或排队任务，请先取消或等待收尾完成"
                );
            }
        }
        String name = uniqueAutoName(
                request.name(),
                current.config(),
                id
        );
        AutoClassifyDefinition replacement = definition(
                id,
                name,
                request.enabled() == null
                        ? existing.enabled()
                        : request.enabled(),
                request.collectionEnabled() == null
                        ? existing.collectionEnabled()
                        : request.collectionEnabled(),
                request.classificationEnabled() == null
                        ? existing.classificationEnabled()
                        : request.classificationEnabled(),
                request.detection(),
                request.cacheRegionRef(),
                request.classificationAreas(),
                request.collectionPriority(),
                request.overflowEnabled() == null ? existing.overflowEnabled()
                        : request.overflowEnabled(),
                request.cleanupPolicy() == null ? existing.cleanupPolicy() : request.cleanupPolicy()
        );
        Set<UUID> oldTaskIds = AutoClassifyTaskFactory.ownedTaskIds(
                List.of(existing)
        );
        Set<UUID> oldTriggerIds = AutoClassifyTaskFactory.ownedTriggerIds(
                List.of(existing)
        );
        List<TaskDefinition> tasks = current.config().tasks().stream()
                .filter(task -> !oldTaskIds.contains(task.id()))
                .toList();
        List<TaskTriggerDefinition> triggers = current.config().triggers()
                .stream()
                .filter(trigger -> !oldTriggerIds.contains(trigger.id()))
                .toList();
        List<TaskDefinition> updatedTasks = new ArrayList<>(tasks);
        updatedTasks.addAll(AutoClassifyTaskFactory.tasks(
                replacement,
                tasks.stream().map(TaskDefinition::name).toList()
        ));
        List<TaskTriggerDefinition> updatedTriggers = new ArrayList<>(triggers);
        updatedTriggers.addAll(AutoClassifyTaskFactory.triggers(
                replacement,
                triggers.stream().map(TaskTriggerDefinition::name).toList()
        ));
        List<AutoClassifyDefinition> definitions = current.config()
                .autoClassifyTasks().stream()
                .map(item -> item.id().equals(id) ? replacement : item)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                updatedTasks,
                current.config().regions(),
                current.config().categoryZones(),
                updatedTriggers,
                current.config().taskFolders(),
                definitions
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return new AutoClassifyTaskResponse(
                saved.revision(),
                autoView(replacement, saved.config())
        );
    }

    @Override
    public synchronized AutoClassifyTaskResponse startAutoClassifyTask(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        AutoClassifyDefinition existing = requireAutoClassify(
                current.config(),
                id
        );
        AutoClassifyDefinition enabled = withEnabled(existing, true);
        List<TaskTriggerDefinition> triggers = new ArrayList<>(
                current.config().triggers().stream()
                        .filter(trigger -> !AutoClassifyTaskFactory.ownedTriggerIds(
                                List.of(existing)
                        ).contains(trigger.id()))
                        .toList()
        );
        triggers.addAll(AutoClassifyTaskFactory.triggers(
                enabled,
                triggers.stream().map(TaskTriggerDefinition::name).toList()
        ));
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                triggers,
                current.config().taskFolders(),
                current.config().autoClassifyTasks().stream()
                        .map(item -> item.id().equals(id) ? enabled : item)
                        .toList()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        TaskDefinition classification = requireTask(
                saved.config(),
                enabled.classificationTaskId()
        );
        runs.start(classification, saved.config(), TaskPriority.BACKGROUND);
        return new AutoClassifyTaskResponse(
                saved.revision(),
                autoView(enabled, saved.config())
        );
    }

    @Override
    public synchronized AutoClassifyTaskResponse stopAutoClassifyTask(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        AutoClassifyDefinition existing = requireAutoClassify(
                current.config(),
                id
        );
        AutoClassifyDefinition disabled = withEnabled(existing, false);
        Set<UUID> triggerIds = AutoClassifyTaskFactory.ownedTriggerIds(
                List.of(existing)
        );
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers().stream()
                        .filter(trigger -> !triggerIds.contains(trigger.id()))
                        .toList(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks().stream()
                        .map(item -> item.id().equals(id) ? disabled : item)
                        .toList()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return new AutoClassifyTaskResponse(
                saved.revision(),
                autoView(disabled, saved.config())
        );
    }

    @Override
    public synchronized AutoClassifyTaskResponse deleteAutoClassifyTask(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        AutoClassifyDefinition existing = requireAutoClassify(
                current.config(),
                id
        );
        Set<UUID> taskIds = AutoClassifyTaskFactory.ownedTaskIds(
                List.of(existing)
        );
        for (UUID taskId : taskIds) {
            if (runs.hasPendingTask(taskId)) {
                throw new IllegalStateException(
                        "自动分类仍有活动或排队任务，请先取消或等待收尾完成"
                );
            }
        }
        Set<UUID> triggerIds = AutoClassifyTaskFactory.ownedTriggerIds(
                List.of(existing)
        );
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks().stream()
                        .filter(task -> !taskIds.contains(task.id()))
                        .toList(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers().stream()
                        .filter(trigger -> !triggerIds.contains(trigger.id()))
                        .toList(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks().stream()
                        .filter(item -> !item.id().equals(id))
                        .toList()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return new AutoClassifyTaskResponse(
                saved.revision(),
                autoView(withEnabled(existing, false), saved.config())
        );
    }

    @Override
    public synchronized TaskDefinition task(UUID id) {
        return requireTask(configs.read().config(), id);
    }

    @Override
    public synchronized TaskListResponse createTask(TaskRequest request) {
        V2ConfigSnapshot current = configs.read();
        TaskDefinition task = new TaskDefinition(
                UUID.randomUUID(),
                request.name(),
                request.priority(),
                request.repeatWhenIdle(),
                steps(request.steps()),
                request.interruptionMode(),
                request.interruptionTaskRef(),
                request.defaultInterruptionFlow(),
                request.requeueAfterInterruption(),
                request.folderId(),
                request.cleanupPolicy()
        );
        List<TaskDefinition> tasks = new ArrayList<>(current.config().tasks());
        tasks.add(task);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks,
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        TaskListResponse response = taskResponse(saved);
        return new TaskListResponse(
                response.revision(),
                task.id(),
                response.tasks()
        );
    }

    @Override
    public synchronized TaskListResponse updateTask(
            UUID id,
            TaskUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        TaskDefinition existing = requireTask(current.config(), id);
        TaskDefinition updatedTask = new TaskDefinition(
                existing.id(),
                request.name(),
                request.priority(),
                request.repeatWhenIdle(),
                steps(request.steps()),
                request.interruptionMode(),
                request.interruptionTaskRef(),
                request.defaultInterruptionFlow(),
                request.requeueAfterInterruption(),
                request.folderId(),
                request.cleanupPolicy() == null ? existing.cleanupPolicy() : request.cleanupPolicy()
        );
        List<TaskDefinition> tasks = current.config().tasks().stream()
                .map(task -> task.id().equals(id) ? updatedTask : task)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks,
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return taskResponse(saved);
    }

    @Override
    public synchronized TaskListResponse deleteTask(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        TaskDefinition existing = requireTask(current.config(), id);
        ResourceReference taskReference = ResourceReference.of(
                ResourceType.TASK,
                id
        );
        List<String> references = current.config().tasks().stream()
                .filter(task -> !task.id().equals(id))
                .filter(task -> TaskFlowNormalizer.references(
                        task,
                        taskReference
                ) || (existing.defaultInterruptionFlow()
                        && task.interruptionMode() == TaskInterruptionMode.DEFAULT))
                .map(TaskDefinition::name)
                .toList();
        if (!references.isEmpty()) {
            throw new TaskInUseException(references);
        }
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks().stream()
                        .filter(task -> !task.id().equals(id))
                        .toList(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers().stream()
                        .filter(trigger -> !trigger.targetTaskId().equals(id))
                        .toList(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return taskResponse(saved);
    }

    @Override
    public synchronized TaskTriggerListResponse taskTriggers() {
        return taskTriggerResponse(configs.read());
    }

    @Override
    public synchronized TaskTriggerDefinition taskTrigger(UUID id) {
        return requireTaskTrigger(configs.read().config(), id);
    }

    @Override
    public synchronized TaskTriggerListResponse createTaskTrigger(
            TaskTriggerRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        TaskTriggerDefinition trigger = new TaskTriggerDefinition(
                UUID.randomUUID(),
                request.name(),
                request.type(),
                request.targetTaskId(),
                request.priority(),
                request.intervalTicks(),
                request.cooldownTicks(),
                request.coordinateId(),
                request.itemFilter(),
                request.foodLevelThreshold(),
                request.cacheRegionId()
        );
        List<TaskTriggerDefinition> triggers = new ArrayList<>(
                current.config().triggers()
        );
        triggers.add(trigger);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                triggers,
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return taskTriggerResponse(saved);
    }

    @Override
    public synchronized TaskTriggerListResponse updateTaskTrigger(
            UUID id,
            TaskTriggerUpdateRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireTaskTrigger(current.config(), id);
        TaskTriggerDefinition replacement = new TaskTriggerDefinition(
                id,
                request.name(),
                request.type(),
                request.targetTaskId(),
                request.priority(),
                request.intervalTicks(),
                request.cooldownTicks(),
                request.coordinateId(),
                request.itemFilter(),
                request.foodLevelThreshold(),
                request.cacheRegionId()
        );
        List<TaskTriggerDefinition> triggers = current.config().triggers()
                .stream()
                .map(trigger -> trigger.id().equals(id)
                        ? replacement
                        : trigger)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                triggers,
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return taskTriggerResponse(saved);
    }

    @Override
    public synchronized TaskTriggerListResponse deleteTaskTrigger(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireTaskTrigger(current.config(), id);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers().stream()
                        .filter(trigger -> !trigger.id().equals(id))
                        .toList(),
                current.config().taskFolders(),
                current.config().autoClassifyTasks()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return taskTriggerResponse(saved);
    }

    @Override
    public synchronized TaskRunSnapshot runTask(UUID id) {
        return runTask(id, null);
    }

    @Override
    public synchronized TaskRunSnapshot runTask(
            UUID id,
            TaskPriority priorityOverride
    ) {
        V2ConfigSnapshot current = configs.read();
        TaskDefinition task = requireTask(current.config(), id);
        return runs.start(task, current.config(), priorityOverride);
    }

    @Override
    public RunListResponse taskRuns() {
        return new RunListResponse(
                runs.runs(),
                runs.queued(),
                runs.triggerStatuses()
        );
    }

    @Override
    public dev.stowling.v2.runtime.TaskDiagnosticLog.Batch taskDiagnostics(long after, int limit) {
        return runs.diagnostics().read(after, limit);
    }

    @Override
    public dev.stowling.v2.runtime.TaskDiagnosticLog.Batch taskDiagnostics(UUID runId) {
        return runs.diagnostics().readRun(runId);
    }

    @Override
    public void cancelRun(UUID runId) {
        runs.cancel(runId);
    }

    @Override
    public void retryInterruption(UUID runId) {
        runs.retryInterruption(runId);
    }

    @Override
    public void skipInterruption(UUID runId) {
        runs.skipInterruption(runId);
    }

    private static AutoClassifyDefinition requireAutoClassify(
            AppConfig config,
            UUID id
    ) {
        return config.autoClassifyTasks().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException(
                        "auto classify task not found"
                ));
    }

    private static AutoClassifyDefinition definition(
            UUID id,
            String name,
            Boolean enabled,
            Boolean collectionEnabled,
            Boolean classificationEnabled,
            AutoClassifyDetectionRequest detection,
            String cacheRegionRef,
            List<AutoClassifyAreaRequest> requestedAreas,
            TaskPriority collectionPriority,
            boolean overflowEnabled,
            dev.stowling.v2.model.TaskCleanupPolicy cleanupPolicy
    ) {
        if (detection == null) {
            throw new V2ValidationException("detection is required");
        }
        String coordinateRef = detection.coordinateRef();
        if (coordinateRef == null || coordinateRef.isBlank()) {
            throw new V2ValidationException(
                    "detection.coordinateRef is required"
            );
        }
        int pickupRadius = detection.pickupRadius() == null
                ? AutoClassifyDefinition.DEFAULT_PICKUP_RADIUS
                : detection.pickupRadius();
        List<AutoClassifyAreaDefinition> areas = requestedAreas.stream()
                .map(area -> new AutoClassifyAreaDefinition(
                        area.categoryZoneRef(),
                        area.storagePolicy()
                ))
                .toList();
        return new AutoClassifyDefinition(
                id,
                name == null || name.isBlank() ? "自动分类" : name,
                Boolean.TRUE.equals(enabled),
                Boolean.TRUE.equals(collectionEnabled),
                Boolean.TRUE.equals(classificationEnabled),
                coordinateRef,
                pickupRadius,
                cacheRegionRef,
                areas,
                collectionPriority == null
                        ? TaskPriority.NORMAL
                        : collectionPriority,
                overflowEnabled,
                cleanupPolicy
        );
    }

    private static AutoClassifyDefinition withEnabled(
            AutoClassifyDefinition definition,
            boolean enabled
    ) {
        return new AutoClassifyDefinition(
                definition.id(),
                definition.name(),
                enabled,
                definition.collectionEnabled(),
                definition.classificationEnabled(),
                definition.detectionCoordinateRef(),
                definition.pickupRadius(),
                definition.cacheRegionRef(),
                definition.classificationAreas(),
                definition.collectionPriority(),
                definition.overflowEnabled(),
                definition.cleanupPolicy()
        );
    }

    private static AutoClassifyTask autoView(
            AutoClassifyDefinition definition,
            AppConfig config
    ) {
        List<UUID> triggerIds = new ArrayList<>();
        if (definition.enabled()) {
            if (definition.collectionEnabled()) {
                triggerIds.add(definition.collectionTriggerId());
            }
        }
        return new AutoClassifyTask(
                definition.id(),
                definition.name(),
                definition.enabled(),
                definition.collectionEnabled(),
                definition.classificationEnabled(),
                new AutoClassifyDetection(
                        definition.detectionCoordinateRef(),
                        definition.pickupRadius()
                ),
                definition.cacheRegionRef(),
                definition.classificationAreas(),
                definition.collectionPriority(),
                TaskPriority.BACKGROUND,
                ResourceReference.of(ResourceType.TASK, definition.id())
                        .encode(),
                ResourceReference.of(
                        ResourceType.TASK,
                        definition.classificationTaskId()
                ).encode(),
                ResourceReference.of(
                        ResourceType.TASK,
                        definition.interruptionTaskId()
                ).encode(),
                triggerIds,
                definition.overflowEnabled(),
                definition.cleanupPolicy()
        );
    }

    private static String uniqueAutoName(
            String candidate,
            AppConfig config,
            UUID updatingId
    ) {
        String requested = candidate == null || candidate.isBlank()
                ? "自动分类"
                : candidate.trim();
        if (requested.length() > 80) {
            throw new V2ValidationException(
                    "auto classify name must contain at most 80 characters"
            );
        }
        Set<String> unavailable = new LinkedHashSet<>();
        Set<UUID> owned = updatingId == null
                ? Set.of()
                : config.autoClassifyTasks().stream()
                        .filter(item -> item.id().equals(updatingId))
                        .findFirst()
                        .map(item -> AutoClassifyTaskFactory.ownedTaskIds(
                                List.of(item)
                        ))
                        .orElseGet(Set::of);
        config.tasks().stream()
                .filter(task -> !owned.contains(task.id()))
                .map(TaskDefinition::name)
                .forEach(unavailable::add);
        config.autoClassifyTasks().stream()
                .filter(item -> !item.id().equals(updatingId))
                .map(AutoClassifyDefinition::name)
                .forEach(unavailable::add);
        return AutoClassifyTaskFactory.uniqueName(requested, unavailable);
    }

    private static CoordinateListResponse coordinateResponse(
            V2ConfigSnapshot snapshot
    ) {
        return new CoordinateListResponse(
                snapshot.revision(),
                snapshot.config().coordinates()
        );
    }

    private static TaskListResponse taskResponse(V2ConfigSnapshot snapshot) {
        Set<UUID> derivedIds = AutoClassifyTaskFactory.ownedTaskIds(
                snapshot.config().autoClassifyTasks()
        );
        return new TaskListResponse(
                snapshot.revision(),
                snapshot.config().tasks().stream()
                        .filter(task -> !derivedIds.contains(task.id()))
                        .toList()
        );
    }

    private static TaskFolderListResponse taskFolderResponse(
            V2ConfigSnapshot snapshot
    ) {
        return new TaskFolderListResponse(
                snapshot.revision(),
                snapshot.config().taskFolders()
        );
    }

    private static TaskTriggerListResponse taskTriggerResponse(
            V2ConfigSnapshot snapshot
    ) {
        Set<UUID> derivedIds = AutoClassifyTaskFactory.ownedTriggerIds(
                snapshot.config().autoClassifyTasks()
        );
        return new TaskTriggerListResponse(
                snapshot.revision(),
                snapshot.config().triggers().stream()
                        .filter(trigger -> !derivedIds.contains(trigger.id()))
                        .toList()
        );
    }

    private static RegionListResponse regionResponse(V2ConfigSnapshot snapshot) {
        return new RegionListResponse(
                snapshot.revision(),
                snapshot.config().regions()
        );
    }

    private static CategoryZoneListResponse categoryZoneResponse(
            V2ConfigSnapshot snapshot
    ) {
        return new CategoryZoneListResponse(
                snapshot.revision(),
                snapshot.config().categoryZones()
        );
    }

    private static void requireRevision(
            V2ConfigSnapshot snapshot,
            String revision
    ) {
        if (!snapshot.revision().equals(revision)) {
            throw new V2RevisionConflictException();
        }
    }

    private static CoordinatePoint requireCoordinate(
            AppConfig config,
            UUID id
    ) {
        return config.coordinates().stream()
                .filter(point -> point.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException(
                        "coordinate not found"
                ));
    }

    private static TaskDefinition requireTask(AppConfig config, UUID id) {
        return config.tasks().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException("task not found"));
    }

    private static TaskFolderDefinition requireTaskFolder(
            AppConfig config,
            UUID id
    ) {
        return config.taskFolders().stream()
                .filter(folder -> folder.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException(
                        "task folder not found"
                ));
    }

    private static TaskDefinition withFolder(
            TaskDefinition task,
            UUID folderId
    ) {
        return new TaskDefinition(
                task.id(),
                task.name(),
                task.priority(),
                task.repeatWhenIdle(),
                task.steps(),
                task.interruptionMode(),
                task.interruptionTaskRef(),
                task.defaultInterruptionFlow(),
                task.requeueAfterInterruption(),
                folderId,
                task.cleanupPolicy()
        );
    }

    private static TaskTriggerDefinition requireTaskTrigger(
            AppConfig config,
            UUID id
    ) {
        return config.triggers().stream()
                .filter(trigger -> trigger.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException(
                        "task trigger not found"
                ));
    }

    private static RegionDefinition requireRegion(AppConfig config, UUID id) {
        return config.regions().stream()
                .filter(region -> region.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException("region not found"));
    }

    private static CategoryZoneDefinition requireCategoryZone(
            AppConfig config,
            UUID id
    ) {
        return config.categoryZones().stream()
                .filter(zone -> zone.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException(
                        "category zone not found"
                ));
    }

    private static List<TaskStep> steps(List<TaskStepRequest> requestedSteps) {
        List<TaskStep> result = new ArrayList<>();
        for (TaskStepRequest step : requestedSteps) {
            TaskStepKind kind = step.kind();
            String moduleId = kind == TaskStepKind.ACTION
                    ? requireText(step.moduleId(), "moduleId")
                    : "";
            result.add(new TaskStep(
                    stepId(step.id()),
                    kind,
                    moduleId,
                    step.parameters(),
                    stepsAllowEmpty(step.steps()),
                    stepsAllowEmpty(step.otherwise())
            ));
        }
        return List.copyOf(result);
    }

    private static List<TaskStep> stepsAllowEmpty(
            List<TaskStepRequest> requestedSteps
    ) {
        if (requestedSteps.isEmpty()) {
            return List.of();
        }
        return steps(requestedSteps);
    }

    private CategoryZoneDefinition categoryZone(
            UUID id,
            String name,
            List<CategoryBindingRequest> requestedBindings,
            CategoryZoneExpansionRequest requestedExpansion,
            CategoryZoneDefinition existing
    ) {
        if (requestedExpansion == null) {
            return new CategoryZoneDefinition(
                    id,
                    name,
                    bindings(
                            requestedBindings,
                            existing == null ? List.of() : existing.bindings()
                    )
            );
        }
        List<CategoryBindingDefinition> prior;
        if (existing == null) {
            prior = List.of();
        } else if (existing.expansion() == null) {
            prior = existing.bindings();
        } else {
            prior = existing.bindings().subList(
                    0,
                    existing.expansion().templateCount()
            );
        }
        List<CategoryBindingDefinition> templates = bindings(
                requestedBindings,
                prior
        );
        CategoryZoneExpansionDefinition expansion = expansion(
                requestedExpansion,
                templates.size()
        );
        try {
            return new CategoryZoneDefinition(
                    id,
                    name,
                    CategoryZoneTiler.expand(expansion, templates),
                    expansion
            );
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(exception.getMessage(), exception);
        }
    }

    private static CategoryZoneExpansionDefinition expansion(
            CategoryZoneExpansionRequest request,
            int templateCount
    ) {
        if (request.direction() == null || request.direction().isBlank()) {
            throw new V2ValidationException("请选择自动铺满方向");
        }
        CategoryZoneExpansionAxis direction;
        try {
            direction = CategoryZoneExpansionAxis.valueOf(
                    request.direction().trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException("自动铺满方向无效", exception);
        }
        try {
            return new CategoryZoneExpansionDefinition(
                    new AreaBounds(
                            request.firstX(),
                            request.firstY(),
                            request.firstZ(),
                            request.secondX(),
                            request.secondY(),
                            request.secondZ()
                    ),
                    direction,
                    request.step(),
                    templateCount
            );
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(exception.getMessage(), exception);
        }
    }

    private List<CategoryBindingDefinition> bindings(
            List<CategoryBindingRequest> requested,
            List<CategoryBindingDefinition> existing
    ) {
        if (requested.isEmpty()) {
            throw new V2ValidationException(
                    "category zones require at least one binding"
            );
        }
        List<CategoryBindingDefinition> result = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            CategoryBindingRequest binding = requested.get(index);
            categoryRules.require(binding.ruleId());
            CategoryBindingDefinition existingBinding =
                    index < existing.size() ? existing.get(index) : null;
            UUID id = existingBinding != null
                    ? existingBinding.id()
                    : UUID.randomUUID();
            result.add(new CategoryBindingDefinition(
                    id,
                    new AreaBounds(
                            binding.markerFirstX(),
                            binding.markerFirstY(),
                            binding.markerFirstZ(),
                            binding.markerSecondX(),
                            binding.markerSecondY(),
                            binding.markerSecondZ()
                    ),
                    new AreaBounds(
                            binding.storageFirstX(),
                            binding.storageFirstY(),
                            binding.storageFirstZ(),
                            binding.storageSecondX(),
                            binding.storageSecondY(),
                            binding.storageSecondZ()
                    ),
                    binding.ruleId(),
                    binding.ignoredMarkerBlockIds(),
                    containerOverrides(
                            binding.containerOverrides(),
                            existingBinding
                    ),
                    binding.standingArea(),
                    binding.storagePolicy(),
                    binding.wholeStorage()
            ));
        }
        return List.copyOf(result);
    }

    private static List<CategoryContainerOverrideDefinition> containerOverrides(
            List<CategoryContainerOverrideRequest> requested,
            CategoryBindingDefinition existing
    ) {
        List<CategoryContainerOverrideDefinition> existingOverrides =
                existing == null
                        ? List.of()
                        : existing.containerOverrides();
        List<CategoryContainerOverrideDefinition> result =
                new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            CategoryContainerOverrideRequest override = requested.get(index);
            if (override.itemIds().isEmpty()) {
                throw new V2ValidationException(
                        "container override requires at least one item id"
                );
            }
            UUID id = index < existingOverrides.size()
                    ? existingOverrides.get(index).id()
                    : UUID.randomUUID();
            result.add(new CategoryContainerOverrideDefinition(
                    id,
                    new BlockPosition(
                            override.storageX(),
                            override.storageY(),
                            override.storageZ()
                    ),
                    parseOverrideMode(override.mode()),
                    override.itemIds()
            ));
        }
        return List.copyOf(result);
    }

    private static CategoryContainerOverrideMode parseOverrideMode(
            String value
    ) {
        try {
            return CategoryContainerOverrideMode.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException(
                    "container override mode is not supported"
            );
        }
    }

    private static UUID stepId(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value.toLowerCase(Locale.ROOT))) {
                throw new V2ValidationException(
                        "step id must be a canonical UUID"
                );
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new V2ValidationException("step id must be a UUID");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new V2ValidationException(field + " must not be blank");
        }
        return value.trim();
    }
}
