package dev.stowling.v2.control;

import dev.stowling.v2.runtime.TaskRunSnapshot;
import java.util.List;

public record RunListResponse(List<TaskRunSnapshot> runs) {
    public RunListResponse {
        runs = List.copyOf(runs);
    }
}
