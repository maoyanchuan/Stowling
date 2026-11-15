package dev.stowling.v2.control;

import dev.stowling.control.InventoryInfo;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2ConfigSnapshot;
import dev.stowling.v2.config.V2RevisionConflictException;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.module.TaskModuleCatalog;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.TaskRunSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class V2ControlServiceImpl implements V2ControlService {
    private final ConfigStore configs;
    private final TaskRunManager runs;
    private final Supplier<List<InventoryInfo>> inventory;

    public V2ControlServiceImpl(
            ConfigStore configs,
            TaskRunManager runs,
            Supplier<List<InventoryInfo>> inventory
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
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
        V2ConfigSnapshot snapshot = configs.read();
        return coordinateResponse(snapshot);
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
        List<CoordinatePoint> coordinates = new ArrayList<>(current.config().coordinates());
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
                current.config().tasks()
        );
        return coordinateResponse(configs.save(current.revision(), updated));
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
                .map(point -> point.id().equals(id)
                        ? replacement
                        : point)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                coordinates,
                current.config().tasks()
        );
        return coordinateResponse(configs.save(current.revision(), updated));
    }

    @Override
    public synchronized CoordinateListResponse deleteCoordinate(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireCoordinate(current.config(), id);
        List<String> references = current.config().tasks().stream()
                .filter(task -> task.steps().stream().anyMatch(step ->
                        id.equals(UUID.fromString(
                                step.parameters().get(ConfigValidator.COORDINATE_ID)
                        ))))
                .map(TaskDefinition::name)
                .toList();
        if (!references.isEmpty()) {
            throw new CoordinateInUseException(references);
        }
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates().stream()
                        .filter(point -> !point.id().equals(id))
                        .toList(),
                current.config().tasks()
        );
        return coordinateResponse(configs.save(current.revision(), updated));
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
                steps(request.steps())
        );
        ConfigValidator.requireValid(new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                List.of(task)
        ));
        List<TaskDefinition> tasks = new ArrayList<>(current.config().tasks());
        tasks.add(task);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks
        );
        return taskResponse(configs.save(current.revision(), updated));
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
                steps(request.steps())
        );
        List<TaskDefinition> tasks = current.config().tasks().stream()
                .map(task -> task.id().equals(id) ? updatedTask : task)
                .toList();
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                tasks
        );
        return taskResponse(configs.save(current.revision(), updated));
    }

    @Override
    public synchronized TaskListResponse deleteTask(
            UUID id,
            RevisionRequest request
    ) {
        V2ConfigSnapshot current = configs.read();
        requireRevision(current, request.revision());
        requireTask(current.config(), id);
        AppConfig updated = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                current.config().coordinates(),
                current.config().tasks().stream()
                        .filter(task -> !task.id().equals(id))
                        .toList()
        );
        return taskResponse(configs.save(current.revision(), updated));
    }

    @Override
    public synchronized TaskRunSnapshot runTask(UUID id) {
        V2ConfigSnapshot current = configs.read();
        TaskDefinition task = requireTask(current.config(), id);
        return runs.start(task, current.config().coordinates());
    }

    @Override
    public RunListResponse taskRuns() {
        return new RunListResponse(runs.runs());
    }

    @Override
    public void cancelRun(UUID runId) {
        runs.cancel(runId);
    }

    private static CoordinateListResponse coordinateResponse(V2ConfigSnapshot snapshot) {
        return new CoordinateListResponse(
                snapshot.revision(),
                snapshot.config().coordinates()
        );
    }

    private static TaskListResponse taskResponse(V2ConfigSnapshot snapshot) {
        return new TaskListResponse(snapshot.revision(), snapshot.config().tasks());
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
                .orElseThrow(() -> new V2NotFoundException("coordinate not found"));
    }

    private static TaskDefinition requireTask(AppConfig config, UUID id) {
        return config.tasks().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new V2NotFoundException("task not found"));
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

    private static UUID stepId(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value.toLowerCase(java.util.Locale.ROOT))) {
                throw new V2ValidationException("step id must be a canonical UUID");
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
