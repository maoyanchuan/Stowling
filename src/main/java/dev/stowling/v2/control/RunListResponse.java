package dev.stowling.v2.control;

import dev.stowling.v2.runtime.TaskRunSnapshot;
import dev.stowling.v2.runtime.TaskTriggerSnapshot;
import java.util.List;

public record RunListResponse(
        List<TaskRunSnapshot> runs,
        List<TaskRunSnapshot> queue,
        List<TaskTriggerSnapshot> triggers
) {
    public RunListResponse {
        runs = List.copyOf(runs);
        queue = List.copyOf(queue);
        triggers = List.copyOf(triggers);
    }

    public RunListResponse(List<TaskRunSnapshot> runs) {
        this(runs, List.of(), List.of());
    }

    public RunListResponse(
            List<TaskRunSnapshot> runs,
            List<TaskRunSnapshot> queue
    ) {
        this(runs, queue, List.of());
    }
}
