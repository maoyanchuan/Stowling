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
    StatusResponse status();

    List<InventoryInfo> inventory();

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
