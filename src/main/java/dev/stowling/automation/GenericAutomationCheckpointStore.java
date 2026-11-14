package dev.stowling.automation;

import java.util.Optional;
import java.util.UUID;

public interface GenericAutomationCheckpointStore {
    Optional<GenericAutomationCheckpoint> load(UUID taskId);

    void save(GenericAutomationCheckpoint checkpoint);

    void delete(UUID taskId);
}
