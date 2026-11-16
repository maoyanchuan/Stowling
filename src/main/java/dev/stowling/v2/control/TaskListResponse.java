package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskDefinition;
import java.util.List;
import java.util.UUID;

public record TaskListResponse(
        String revision,
        UUID taskId,
        List<TaskDefinition> tasks
) {
    public TaskListResponse {
        tasks = List.copyOf(tasks);
    }

    public TaskListResponse(String revision, List<TaskDefinition> tasks) {
        this(revision, null, tasks);
    }
}
