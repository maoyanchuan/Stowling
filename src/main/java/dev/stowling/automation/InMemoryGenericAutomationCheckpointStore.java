package dev.stowling.automation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryGenericAutomationCheckpointStore
        implements GenericAutomationCheckpointStore {
    private final Map<UUID, GenericAutomationCheckpoint> checkpoints =
            new ConcurrentHashMap<>();

    @Override
    public Optional<GenericAutomationCheckpoint> load(UUID taskId) {
        return Optional.ofNullable(checkpoints.get(Objects.requireNonNull(taskId, "taskId")));
    }

    @Override
    public void save(GenericAutomationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoints.put(checkpoint.taskId(), checkpoint);
    }

    @Override
    public void delete(UUID taskId) {
        checkpoints.remove(Objects.requireNonNull(taskId, "taskId"));
    }
}
