package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskSchedule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record TaskListResponse(
        String revision,
        UUID taskId,
        List<TaskDefinition> tasks,
        Map<UUID, TaskSchedule> schedules,
        Set<UUID> enabledSchedules
) {
    public TaskListResponse {
        tasks = List.copyOf(tasks);
        schedules = Map.copyOf(schedules);
        enabledSchedules = Set.copyOf(enabledSchedules);
    }

    public TaskListResponse(String revision, UUID taskId, List<TaskDefinition> tasks) {
        this(revision, taskId, tasks, Map.of(), Set.of());
    }

    public TaskListResponse(String revision, List<TaskDefinition> tasks) {
        this(revision, null, tasks);
    }
}
