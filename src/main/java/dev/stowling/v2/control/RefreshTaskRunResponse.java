package dev.stowling.v2.control;

import dev.stowling.v2.runtime.TaskRunState;
import java.time.Instant;

public record RefreshTaskRunResponse(
        String revision,
        boolean scheduleEnabled,
        TaskRunState runState,
        Instant nextAt
) {
}
