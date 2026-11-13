package dev.stowling.config;

import java.util.Objects;

public record ConfigFileSnapshot(String revision, StowlingConfig config) {
    public ConfigFileSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        config = Objects.requireNonNull(config, "config");
    }
}
