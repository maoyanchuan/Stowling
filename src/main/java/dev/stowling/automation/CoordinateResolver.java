package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Optional;

@FunctionalInterface
public interface CoordinateResolver {
    Optional<BlockPosition> resolve(String coordinateId);
}
