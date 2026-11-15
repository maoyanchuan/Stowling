package dev.stowling.v2.category;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;

public record CategoryMarker(
        BlockPosition position,
        String itemId,
        CategoryMarkerSource source,
        int slotIndex
) {
    public CategoryMarker {
        position = Objects.requireNonNull(position, "position");
        itemId = Objects.requireNonNull(itemId, "itemId");
        source = Objects.requireNonNull(source, "source");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (slotIndex < -1) {
            throw new IllegalArgumentException("slotIndex must be -1 or greater");
        }
    }

    public CategoryMarker(BlockPosition position, String itemId) {
        this(position, itemId, CategoryMarkerSource.PLACED_ITEM, -1);
    }
}
