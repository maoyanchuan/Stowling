package dev.stowling.client.automation;

import dev.stowling.automation.CoordinateResolver;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ConfigCoordinateResolver implements CoordinateResolver {
    private final Supplier<StowlingConfig> config;

    public ConfigCoordinateResolver(Supplier<StowlingConfig> config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Optional<BlockPosition> resolve(String coordinateId) {
        Objects.requireNonNull(coordinateId, "coordinateId");
        return config.get().coordinatePoints().stream()
                .filter(coordinate -> coordinate.id().equals(coordinateId))
                .map(coordinate -> coordinate.position())
                .findFirst();
    }
}
