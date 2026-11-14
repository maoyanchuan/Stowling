package dev.stowling.control;

import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskScheduleInfo;
import dev.stowling.schedule.VersionedTaskDefinition;
import java.util.List;

public interface StowlingControlService
        extends TaskControl, AuditControl, ConfigControl, AutomationControl {
    ControlStatus status();

    List<InventoryInfo> inventory();

    List<PickupSiteInfo> pickupSites();

    List<PickupWorkflowInfo> pickupWorkflows();

    List<ContainerInfo> containers();

    default List<TaskDefinition> taskDefinitions() {
        return List.of();
    }

    default VersionedTaskDefinition taskDefinition(TaskKey key) {
        throw new UnsupportedOperationException("Task definitions are unavailable");
    }

    default VersionedTaskDefinition createTaskDefinition(TaskDefinition definition) {
        throw new UnsupportedOperationException("Task definitions are unavailable");
    }

    default VersionedTaskDefinition updateTaskDefinition(
            TaskKey key, String revision, TaskDefinition definition
    ) {
        throw new UnsupportedOperationException("Task definitions are unavailable");
    }

    default void deleteTaskDefinition(TaskKey key, String revision) {
        throw new UnsupportedOperationException("Task definitions are unavailable");
    }

    default List<TaskScheduleInfo> taskScheduling() {
        return List.of();
    }

    default void enableTaskDefinition(TaskKey key) {
        throw new UnsupportedOperationException("Task scheduling is unavailable");
    }

    default void disableTaskDefinition(TaskKey key) {
        throw new UnsupportedOperationException("Task scheduling is unavailable");
    }

    default TaskInfo runTaskDefinition(TaskKey key) {
        throw new UnsupportedOperationException("Task scheduling is unavailable");
    }

    default PickupConfigSnapshot taskResources() {
        return pickupConfig();
    }

    default PickupConfigSnapshot updateTaskResources(PickupConfigUpdateRequest request) {
        throw new UnsupportedOperationException("Task resources are unavailable");
    }

    @Deprecated
    ControlStatus pause();

    @Deprecated
    ControlStatus resume();
}
