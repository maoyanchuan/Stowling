package dev.stowling.v2.control;

import java.util.List;

public record TaskUpdateRequest(
        String revision,
        String name,
        List<TaskStepRequest> steps
) {
    public TaskUpdateRequest {
        steps = List.copyOf(steps);
    }
}
