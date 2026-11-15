package dev.stowling.v2.runtime;

import java.util.Objects;
import java.util.UUID;

public record TaskWatchSnapshot(
        UUID taskId,
        String taskName,
        UUID coordinateId,
        String coordinateName,
        String itemFilter,
        TaskWatchState state,
        String message,
        int matchedDrops
) {
    public TaskWatchSnapshot {
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskName = Objects.requireNonNull(taskName, "taskName");
        coordinateId = Objects.requireNonNull(coordinateId, "coordinateId");
        coordinateName = Objects.requireNonNull(
                coordinateName,
                "coordinateName"
        );
        state = Objects.requireNonNull(state, "state");
        message = Objects.requireNonNull(message, "message");
    }
}
