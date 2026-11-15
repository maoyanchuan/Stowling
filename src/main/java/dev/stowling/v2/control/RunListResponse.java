package dev.stowling.v2.control;

import dev.stowling.v2.runtime.TaskRunSnapshot;
import java.util.List;

public record RunListResponse(
        List<TaskRunSnapshot> runs,
        List<TaskRunSnapshot> queue
) {
    public RunListResponse {
        runs = List.copyOf(runs);
        queue = List.copyOf(queue);
    }

    public RunListResponse(List<TaskRunSnapshot> runs) {
        this(runs, List.of());
    }
}
