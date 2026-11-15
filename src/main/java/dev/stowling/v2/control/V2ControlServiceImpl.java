package dev.stowling.v2.control;

import dev.stowling.control.InventoryInfo;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2ConfigSnapshot;
import dev.stowling.v2.config.V2RevisionConflictException;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.module.TaskModuleCatalog;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.TaskRunSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class V2ControlServiceImpl implements V2ControlService {
    private final ConfigStore configs;
    private final TaskRunManager runs;
    private final Supplier<List<InventoryInfo>> inventory;
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
        runs.refreshTriggers(configs.read().config());
    }

    @Override
    public synchronized StatusResponse status() {
        String state = runs.status()
                .map(run -> run.state().name())
                .orElse("IDLE");
        return new StatusResponse(state, Instant.now());
    }

    @Override
    public List<InventoryInfo> inventory() {
        return List.copyOf(inventory.get());
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
                current.config().triggers()
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
                current.config().triggers()
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
        references.addAll(current.config().tasks().stream()
                .filter(task -> referencesCoordinate(task, coordinateId))
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
                current.config().triggers()
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
                current.config().triggers()
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
                current.config().triggers()
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
        return regionResponse(configs.save(current.revision(), new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks(),
                current.config().regions().stream()
                        .filter(region -> !region.id().equals(id))
                        .toList(),
                current.config().categoryZones(),
                current.config().triggers()
        )));
    }

    @Override
    public CategoryRuleListResponse categoryRules() {
        return new CategoryRuleListResponse(categoryRules.definitions());
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
        zones.add(new CategoryZoneDefinition(
                UUID.randomUUID(),
                request.name(),
                bindings(request.bindings(), List.of())
        ));
        return categoryZoneResponse(configs.save(
                current.revision(),
                new AppConfig(
                        AppConfig.CURRENT_SCHEMA_VERSION,
                        current.config().coordinates(),
                        current.config().tasks(),
                        current.config().regions(),
                        zones,
                        current.config().triggers()
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
        CategoryZoneDefinition replacement = new CategoryZoneDefinition(
                existing.id(),
                request.name(),
                bindings(request.bindings(), existing.bindings())
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
                        current.config().triggers()
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
        String zoneId = id.toString();
        List<String> references = current.config().tasks().stream()
                .filter(task -> task.steps().stream().anyMatch(step ->
                        zoneId.equals(step.parameters().get(
                                ConfigValidator.CATEGORY_ZONE_ID
                        ))))
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
                        current.config().triggers()
                )
        ));
    }

    @Override
    public synchronized TaskListResponse tasks() {
        return taskResponse(configs.read());
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
                steps(request.steps())
        );
        List<TaskDefinition> tasks = new ArrayList<>(current.config().tasks());
        tasks.add(task);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks,
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers()
        );
        V2ConfigSnapshot saved = configs.save(current.revision(), updated);
        runs.refreshTriggers(saved.config());
        return taskResponse(saved);
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
                steps(request.steps())
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
                current.config().triggers()
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
        requireTask(current.config(), id);
        List<String> triggerNames = current.config().triggers().stream()
                .filter(trigger -> trigger.targetTaskId().equals(id))
                .map(TaskTriggerDefinition::name)
                .toList();
        if (!triggerNames.isEmpty()) {
            throw new TaskInUseException(triggerNames);
        }
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks().stream()
                        .filter(task -> !task.id().equals(id))
                        .toList(),
                current.config().regions(),
                current.config().categoryZones(),
                current.config().triggers()
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
                request.foodLevelThreshold()
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
                triggers
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
                request.foodLevelThreshold()
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
                triggers
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
                        .toList()
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
        return runs.start(
                task,
                current.config().coordinates(),
                current.config().regions(),
                current.config().categoryZones(),
                priorityOverride
        );
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
    public void cancelRun(UUID runId) {
        runs.cancel(runId);
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
        return new TaskListResponse(
                snapshot.revision(),
                snapshot.config().tasks()
        );
    }

    private static boolean referencesCoordinate(
            TaskDefinition task,
            String coordinateId
    ) {
        return task.steps().stream().anyMatch(step ->
                coordinateId.equals(
                        step.parameters().get(ConfigValidator.COORDINATE_ID)
                )
        );
    }

    private static TaskTriggerListResponse taskTriggerResponse(
            V2ConfigSnapshot snapshot
    ) {
        return new TaskTriggerListResponse(
                snapshot.revision(),
                snapshot.config().triggers()
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
        if (requestedSteps.isEmpty()) {
            throw new V2ValidationException("tasks require at least one step");
        }
        return requestedSteps.stream()
                .map(step -> new TaskStep(
                        stepId(step.id()),
                        requireText(step.moduleId(), "moduleId"),
                        step.parameters()
                ))
                .toList();
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
                    )
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
