package dev.stowling.v2.control;

import dev.stowling.control.InventoryInfo;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.runtime.TaskRunSnapshot;
import java.util.List;
import java.util.UUID;

public interface V2ControlService {
    default dev.stowling.v2.runtime.TaskDiagnosticLog.Batch taskDiagnostics(long after, int limit) {
        return new dev.stowling.v2.runtime.TaskDiagnosticLog.Batch("", 1, 0, 0, false, List.of());
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

    default PlayerInventoryResponse allocatePlayerInventory(String revision, String key,
            int keepCount) {
        throw new UnsupportedOperationException("player inventory unavailable");
    }

    ModuleListResponse modules();

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

    TaskDefinition task(UUID id);

    TaskListResponse createTask(TaskRequest request);

    TaskListResponse updateTask(UUID id, TaskUpdateRequest request);

    TaskListResponse deleteTask(UUID id, RevisionRequest request);

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

    void retryInterruption(UUID runId);

    void skipInterruption(UUID runId);
}
