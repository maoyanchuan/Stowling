package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;
import java.util.Optional;

public record WorldObservation(
        boolean connected,
        boolean alive,
        String dimension,
        Optional<BlockPosition> playerPosition
) {
    public WorldObservation {
        dimension = Objects.requireNonNull(dimension, "dimension");
        playerPosition = Objects.requireNonNull(playerPosition, "playerPosition");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
        if (!connected && (alive || playerPosition.isPresent())) {
            throw new IllegalArgumentException("disconnected observation cannot have a player");
        }
    }

    public static WorldObservation disconnected() {
        return new WorldObservation(false, false, "unknown", Optional.empty());
    }
}
