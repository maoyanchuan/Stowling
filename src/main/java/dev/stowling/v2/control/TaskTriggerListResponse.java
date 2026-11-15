package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskTriggerDefinition;
import java.util.List;

public record TaskTriggerListResponse(
        String revision,
        List<TaskTriggerDefinition> triggers
) {
    public TaskTriggerListResponse {
        triggers = List.copyOf(triggers);
    }
}
