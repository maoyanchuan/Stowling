package dev.stowling.schedule;

import java.util.Objects;

public record VersionedTaskDefinition(
        String revision,
        TaskDefinition definition
) {
    public VersionedTaskDefinition {
        revision = Objects.requireNonNull(revision, "revision");
        definition = Objects.requireNonNull(definition, "definition");
    }

    public TaskKey key() {
        return definition.key();
    }
}
