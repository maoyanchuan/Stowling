package dev.stowling.automation;

import java.util.Optional;
import java.util.UUID;

public interface AutomationCheckpointStore {
    Optional<AutomationCheckpoint> load(UUID taskId);

    void save(AutomationCheckpoint checkpoint);

    void delete(UUID taskId);
}
