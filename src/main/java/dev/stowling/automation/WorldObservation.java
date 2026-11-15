package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;
import java.util.Optional;

public record WorldObservation(
        boolean connected,
        boolean alive,
        Optional<BlockPosition> playerPosition
) {
    public WorldObservation {
        playerPosition = Objects.requireNonNull(playerPosition, "playerPosition");
        if (!connected && (alive || playerPosition.isPresent())) {
            throw new IllegalArgumentException("disconnected observation cannot have a player");
        }
    }

    public static WorldObservation disconnected() {
        return new WorldObservation(false, false, Optional.empty());
    }
}
