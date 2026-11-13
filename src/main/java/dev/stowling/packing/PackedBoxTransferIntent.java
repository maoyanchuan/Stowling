package dev.stowling.packing;

import dev.stowling.domain.ContainerRef;
import java.util.Objects;

public record PackedBoxTransferIntent(
        String stationId,
        String packedContentsItemId,
        ContainerRef source,
        ContainerRef destination
) {
    public PackedBoxTransferIntent {
        stationId = PackingChecks.requireText(stationId, "stationId");
        packedContentsItemId = PackingChecks.requireText(
                packedContentsItemId,
                "packedContentsItemId"
        );
        source = Objects.requireNonNull(source, "source");
        destination = Objects.requireNonNull(destination, "destination");
    }
}
