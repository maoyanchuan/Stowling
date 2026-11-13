package dev.stowling.packing;

import java.util.Objects;

public record PackingPartialBox(
        String stationId,
        String itemId,
        PackingBoxContents contents
) {
    public PackingPartialBox {
        stationId = PackingChecks.requireText(stationId, "stationId");
        itemId = PackingChecks.requireText(itemId, "itemId");
        contents = Objects.requireNonNull(contents, "contents");
        if (!contents.containsOnly(itemId)) {
            throw new IllegalArgumentException("partial box must contain only its item id");
        }
    }
}
