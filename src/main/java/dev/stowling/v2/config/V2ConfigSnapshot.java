package dev.stowling.v2.config;

import dev.stowling.v2.model.AppConfig;
import java.util.Objects;

public record V2ConfigSnapshot(String revision, AppConfig config) {
    public V2ConfigSnapshot {
        revision = Objects.requireNonNull(revision, "revision");
        config = Objects.requireNonNull(config, "config");
    }
}
