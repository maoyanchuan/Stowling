package dev.stowling.v2.control;

import dev.stowling.control.InventoryInfo;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.runtime.TaskRunSnapshot;
import dev.stowling.v2.model.CoordinateCatalog;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.UUID;

public interface V2ControlService {
    default dev.stowling.v2.scan.CategoryScanDiagnosticLog.History categoryScanDiagnostics() {
        return new dev.stowling.v2.scan.CategoryScanDiagnosticLog.History("", 3, List.of());
    }

    default dev.stowling.v2.scan.CategoryScanDiagnosticLog.Entry categoryScanDiagnostic(UUID id) {
        throw new V2NotFoundException("扫描日志不存在或已轮换");
    }

    default dev.stowling.v2.runtime.TaskDiagnosticLog.Batch taskDiagnostics(long after, int limit) {
        return new dev.stowling.v2.runtime.TaskDiagnosticLog.Batch("", 1, 0, 0, false, List.of());
    }
    default dev.stowling.v2.runtime.TaskDiagnosticLog.Batch taskDiagnostics(UUID runId) {
        var batch = taskDiagnostics(0, dev.stowling.v2.runtime.TaskDiagnosticLog.CAPACITY);
        return new dev.stowling.v2.runtime.TaskDiagnosticLog.Batch(batch.session(),
                batch.firstAvailable(), batch.nextSequence(), batch.latestSequence(),
                batch.truncated(), batch.events().stream()
                        .filter(event -> runId.equals(event.runId())).toList());
    }
    default dev.stowling.inventory.WarehouseInventory warehouseInventory() {
        return new dev.stowling.inventory.WarehouseInventory("UNAVAILABLE", "库存数据源未配置", "",
                java.time.Instant.now(), List.of(), List.of());
    }

    default dev.stowling.v2.config.StorageSettingsStore.Snapshot storageSettings() {
        throw new UnsupportedOperationException("storage settings unavailable");
    }

    default dev.stowling.v2.config.StorageSettingsStore.Snapshot saveStorageSettings(
            String revision, dev.stowling.v2.model.StorageAutomationSettings settings) {
        throw new UnsupportedOperationException("storage settings unavailable");
    }

    StatusResponse status();

    List<InventoryInfo> inventory();

    default PlayerInventoryResponse playerInventory() {
        throw new UnsupportedOperationException("player inventory unavailable");
    }

    default String playerItemIcon(String model) {
        return "";
    }

    default PlayerInventoryResponse setPlayerInventoryLocked(String revision, int slotIndex,
            boolean locked) {
        throw new UnsupportedOperationException("player inventory unavailable");
    }

    ModuleListResponse modules();

    default CoordinateDirectory coordinateDirectory() {
        throw new UnsupportedOperationException("coordinate directory unavailable");
    }

    default CoordinateDirectory editCoordinateDirectory(
            String revision, UnaryOperator<CoordinateCatalog> edit) {
        throw new UnsupportedOperationException("coordinate directory unavailable");
    }

    default CoordinateDirectory placeCoordinateBookmark(String revision, String ref,
            UUID folderId, String beforeRef) {
        throw new UnsupportedOperationException("coordinate directory unavailable");
    }

    record CoordinateDirectory(String revision, List<CoordinatePoint> coordinates,
            List<RegionDefinition> regions, List<CategoryZoneDefinition> categoryZones,
            CoordinateCatalog catalog, Map<String, List<String>> references) {
    }

    CoordinateListResponse coordinates();

    CoordinatePoint coordinate(UUID id);

    CoordinateListResponse createCoordinate(CoordinateRequest request);

    CoordinateListResponse updateCoordinate(UUID id, CoordinateUpdateRequest request);

    CoordinateListResponse deleteCoordinate(UUID id, RevisionRequest request);

    RegionListResponse regions();

    RegionDefinition region(UUID id);

    RegionListResponse createRegion(RegionRequest request);

    RegionListResponse updateRegion(UUID id, RegionUpdateRequest request);

    RegionListResponse deleteRegion(UUID id, RevisionRequest request);

    CategoryRuleListResponse categoryRules();

    CategoryZoneListResponse categoryZones();

    default dev.stowling.v2.scan.CategoryScanResult scanCategoryZone(
            dev.stowling.v2.model.AreaBounds area,
            dev.stowling.v2.scan.CategoryScanDefinition scan,
            dev.stowling.v2.model.CategoryZoneOptions options) {
        throw new IllegalArgumentException("当前未连接游戏扫描服务");
    }

    CategoryZoneDefinition categoryZone(UUID id);

    CategoryZoneListResponse createCategoryZone(CategoryZoneRequest request);

    CategoryZoneListResponse updateCategoryZone(
            UUID id,
            CategoryZoneUpdateRequest request
    );

    CategoryZoneListResponse deleteCategoryZone(
            UUID id,
            RevisionRequest request
    );

    TaskFolderListResponse taskFolders();

    TaskFolderListResponse createTaskFolder(TaskFolderRequest request);

    TaskFolderListResponse updateTaskFolder(
            UUID id,
            TaskFolderUpdateRequest request
    );

    TaskFolderListResponse deleteTaskFolder(
            UUID id,
            RevisionRequest request
    );

    TaskListResponse tasks();

    RefreshTaskRunResponse setRefreshTaskRunning(UUID id, RevisionRequest request, boolean enabled);

    TaskDefinition task(UUID id);

    TaskListResponse createTask(TaskRequest request);

    TaskListResponse updateTask(UUID id, TaskUpdateRequest request);

    TaskListResponse deleteTask(UUID id, RevisionRequest request);

    AutoEatTaskListResponse autoEatTasks();

    AutoEatTaskListResponse saveAutoEatTask(UUID id, String revision, AutoEatTask.Settings settings);

    AutoEatTaskListResponse setAutoEatListening(UUID id, RevisionRequest request, boolean enabled);

    AutoEatTaskListResponse deleteAutoEatTask(UUID id, RevisionRequest request);

    AutoClassifyTaskListResponse autoClassifyTasks();

    AutoClassifyTaskResponse createAutoClassifyTask(
            AutoClassifyTaskRequest request
    );

    AutoClassifyTaskResponse updateAutoClassifyTask(
            UUID id,
            AutoClassifyTaskUpdateRequest request
    );

    AutoClassifyTaskResponse startAutoClassifyTask(
            UUID id,
            RevisionRequest request
    );

    AutoClassifyTaskResponse stopAutoClassifyTask(
            UUID id,
            RevisionRequest request
    );

    AutoClassifyTaskResponse deleteAutoClassifyTask(
            UUID id,
            RevisionRequest request
    );

    TaskTriggerListResponse taskTriggers();

    TaskTriggerDefinition taskTrigger(UUID id);

    TaskTriggerListResponse createTaskTrigger(TaskTriggerRequest request);

    TaskTriggerListResponse updateTaskTrigger(
            UUID id,
            TaskTriggerUpdateRequest request
    );

    TaskTriggerListResponse deleteTaskTrigger(
            UUID id,
            RevisionRequest request
    );

    TaskRunSnapshot runTask(UUID id);

    TaskRunSnapshot runTask(UUID id, TaskPriority priorityOverride);

    RunListResponse taskRuns();

    void cancelRun(UUID runId);

    void forceStopRun(UUID runId);

    void retryInterruption(UUID runId);

    void skipInterruption(UUID runId);
}
