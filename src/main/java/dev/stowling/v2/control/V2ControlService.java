package dev.stowling.v2.control;

import dev.stowling.control.InventoryInfo;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskDefinition;
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

    TaskListResponse tasks();

    TaskDefinition task(UUID id);

    TaskListResponse createTask(TaskRequest request);

    TaskListResponse updateTask(UUID id, TaskUpdateRequest request);

    TaskListResponse deleteTask(UUID id, RevisionRequest request);

    TaskRunSnapshot runTask(UUID id);

    RunListResponse taskRuns();

    void cancelRun(UUID runId);
}
