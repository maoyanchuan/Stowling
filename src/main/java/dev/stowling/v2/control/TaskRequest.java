package dev.stowling.v2.control;

import java.util.List;

public record TaskRequest(String name, List<TaskStepRequest> steps) {
    public TaskRequest {
        steps = List.copyOf(steps);
    }
}
