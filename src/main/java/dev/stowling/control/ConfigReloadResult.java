package dev.stowling.control;

import java.time.Instant;
import java.util.Objects;

public record ConfigReloadResult(boolean reloaded, Instant reloadedAt) {
    public ConfigReloadResult {
        reloadedAt = Objects.requireNonNull(reloadedAt, "reloadedAt");
    }
}
