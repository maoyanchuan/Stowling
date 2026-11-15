package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskDefinition;
import java.util.List;

public record TaskListResponse(String revision, List<TaskDefinition> tasks) {
    public TaskListResponse {
        tasks = List.copyOf(tasks);
    }
}
