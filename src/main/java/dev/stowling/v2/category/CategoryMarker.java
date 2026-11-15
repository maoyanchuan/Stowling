package dev.stowling.v2.category;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;

public record CategoryMarker(
        BlockPosition position,
        String blockId
) {
    public CategoryMarker {
        position = Objects.requireNonNull(position, "position");
        blockId = Objects.requireNonNull(blockId, "blockId");
        if (blockId.isBlank()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
    }
}
