package dev.stowling.schedule;

import java.util.UUID;

public interface TaskRuntimeFactory {
    ScheduledTaskRuntime start(UUID runId, TaskDefinition definition);

    default void queued(UUID runId, TaskDefinition definition) {
        return;
    }

    default void finished(
            UUID runId,
            TaskDefinition definition,
            String status,
            String message
    ) {
        return;
    }
}
